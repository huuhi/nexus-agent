package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.ChatContextFactory;
import com.huzhijian.nexusagentweb.observability.RunMetrics;
import com.huzhijian.nexusagentweb.observability.RunMetricsReporter;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ArtifactService;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final ChatContextFactory chatContextFactory;
    private final ChatHistoryListService chatHistoryListService;
    private final ChatMessageConverter converter;
    private final AgentProperties agentProperties;
    private final SkillLoader skillLoader;
    private final RunMetricsReporter runMetricsReporter;
    private final QuotaService quotaService;
    private final ArtifactService artifactService;
    private final RunUserRegistry runUserRegistry;

    @Override
    public SseEmitter chat(ChatDTO chatDTO) {

        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录!");
        }
//        配额校验放在最前面（P2-8）：超支时直接拒绝，省掉一次完整的模型调用（也不必白建沙盒）
        quotaService.assertWithinQuota(userId);
//        超时由 nexus.agent.sse.timeout 配置（默认 120 秒），必须大于最慢一次模型调用的耗时
        SseEmitter sseEmitter = new SseEmitter(agentProperties.getSse().getTimeout().toMillis());

        List<ChatUserMessage> messages = chatDTO.messages();

//        1) 先转换用户消息：附件元数据由返回值带回，不再写 ThreadLocal
        ChatMessageConverter.ConvertedMessage converted;
        try {
//            图片发成真图还是降级成 URL 文本，取决于本次模型支不支持视觉（2026-10-03）
            ModelCapabilities capabilities = chatContextFactory.resolveCapabilities(chatDTO.model(), userId);
            converted = converter.toContents(messages, capabilities.vision(), userId);
        } catch (ValidationException e) {
//            附件字段缺失 / 归属校验不过 / OSS 下载失败：转换层现在统一抛业务异常（带可读原因），
//            直接透传给前端，别再包成一句没有信息的"参数错误"。
//            ⚠️ 这里以前 catch 的是 com.aliyuncs.exceptions.ClientException —— 那个异常
//            全仓库没有任何地方会抛（OSS 真正抛的是 com.aliyun.oss.*），等于死代码。
            throw e;
        } catch (IOException e) {
            throw new ParserFileException("解析文件失败!");
        }

//        2) 组装本次运行上下文。后续流式回调运行在线程池里，
//        用户ID 与附件元数据只能通过这个对象带过去（ThreadLocal 在那里取不到值）
        String incomingSessionId = chatDTO.sessionId();
        boolean isNewSession = incomingSessionId == null || incomingSessionId.isEmpty();
        String sessionId = isNewSession ? UUID.randomUUID().toString() : incomingSessionId;

//        trace_id：贯穿一次 Run 的日志与 SSE 事件，把「用户看到的报错」与「服务端日志」对上。
//        ⚠️ 必须在 RunContext 之前生成：产物归属（方案 B）要把同一个 runId 同时写进
//        「本次运行落库的每一条历史消息」和「本次运行产出的每个产物」，RunContext 是第一站。
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        RunContext runContext = new RunContext(userId, sessionId, isNewSession, converted.metadata(), runId);

//        工具要拿到 userId，但它运行在**流式回调线程**上 —— 那里 UserContextHolder（ThreadLocal）
//        必然是 null。所以在这里（请求线程，userId 还在）把 sessionId → userId 登记进注册表，
//        工具侧用 @ToolMemoryId（就是 sessionId）反查。
//        ⚠️ 别再让工具直接用 UserContextHolder —— 那是架构上必然取不到值的写法。
        runUserRegistry.register(sessionId, userId);

//        本次运行的指标累加器（token / 工具调用 / 耗时），结尾汇总成一行 RUN 日志（见 §6.12）
        RunMetrics metrics = new RunMetrics(runId, sessionId, userId);

//        3) 构建对话上下文（内部会把 runContext 绑定到记忆存储上）
        ChatContext chatContext = chatContextFactory.create(chatDTO, runContext);
        ChatAssistant chatAssistant = chatContext.getChatAssistant();

//        运行时能力说明必须在调用前注入系统提示词：@SystemMessage 是静态文本，
//        而「有哪些技能 / 哪些 MCP 连不上」都是运行期才知道的，只能通过 Mustache 变量传入
        String runtimeCapabilities = composeCapabilities(
                skillLoader.formatForPrompt(chatDTO.skills(), userId),
                chatContext.getMcpUnavailable());
        log.debug("注入提示词的运行时能力说明：{}", runtimeCapabilities);
        TokenStream tokenStream = chatAssistant.chat(converted.contents(), sessionId, runtimeCapabilities);

        SseResponseConverter writer = SseResponseConverter.builder().chatHistoryListService(chatHistoryListService)
                .sessionId(sessionId)
                .isNewSession(isNewSession)
                .runId(runId)
//                流式增量合并（P2-12）：把逐 token 的推送合成批次，避免上千个 SSE 帧拖垮前后端
                .flushMaxChars(agentProperties.getSse().getFlushMaxChars())
                .flushIntervalMillis(agentProperties.getSse().getFlushInterval().toMillis())
                .message(converter.extractFirstText(messages)).userId(userId)
                .sseEmitter(sseEmitter)
                .build();

//        P2-5：首帧立刻把 runId / sessionId 交给前端（内部幂等，漏调也会被后续事件兜底补发）
        writer.start();

        sseEmitter.onCompletion(writer::finish);
//        超时/断开只标记"连接没了"，**不终止任务**（2026-10-03）：
//        TokenStream 继续跑完，消息落库、标题生成照常 —— 用户刷新页面就能看到完整回复。
//        以前这里是 onError（isFinished=true → 后续产出全被丢弃，但任务还在烧钱），是最坏的组合。
        sseEmitter.onTimeout(() -> writer.disconnect("SSE 连接超时"));
        sseEmitter.onError(writer::onError);

        tokenStream.onPartialThinking(writer::writeThinking)
                .onPartialResponse(writer::writeContent)
                .onPartialToolCallWithContext(writer::writeToolRequestWithStream)
                .onToolExecuted(consumer->{
                    ToolExecutionRequest request = consumer.request();
//                    记录工具调用序列（可观测性）：这是回答「这次对话调了什么工具」的唯一数据源
                    metrics.recordToolExecuted(request.name(), consumer.hasFailed());
//                    writer.writeToolRequest(request.id(),request.name(),request.arguments());
                    writer.writeToolResult(request,consumer.hasFailed(),consumer.result());
//                    P2-10：AI 产出的交付物在这里落库并推 SSE artifact 事件
//                    （放在主流程而不是工具类里，因为只有这一层同时握有 userId / sessionId / SSE writer）
                    publishArtifactIfAny(consumer, runContext, writer);
                })
                .onCompleteResponse(response -> {
//                    token 用量与真实模型名只有在这里拿得到（流式响应的最后一次回调）
                    metrics.recordResponse(response);
                    runMetricsReporter.report(metrics);
//                    配额记账：把本次实际用量累加进 users.token_used（失败会被吞掉，不影响对话）
                    quotaService.recordUsage(userId, totalTokens(response));
                    writer.finish();
                })
                .onError(error -> {
                    metrics.recordError(error);
                    runMetricsReporter.report(metrics);
                    writer.onError(error);
                })
                .start();
        return sseEmitter;
    }

    /**
     * 工具结果里若带 artifact（目前只有 {@code publish_artifact} 会带），落库并推 SSE 事件（P2-10）。
     * <p>
     * 任何异常都被吞掉：产物落库/推送失败不该把对话打挂 ——
     * 文件其实已经生成并上传到 OSS 了，失败的最坏后果只是"用户看不到下载卡片"。
     */
    private void publishArtifactIfAny(ToolExecution execution, RunContext runContext, SseResponseConverter writer) {
        if (execution == null || execution.hasFailed()) {
            return;
        }
        Map<String, Object> artifact = extractArtifact(execution.result());
        if (artifact == null) {
            return;
        }
        try {
            SysFile saved = artifactService.save(artifact, runContext.userId(), runContext.sessionId(), runContext.runId());
            if (saved != null && saved.getId() != null) {
                // 带上落库 id，前端可用它去重与追溯（列产物时也用它）
                artifact.put("id", saved.getId());
            }
            writer.writeArtifact(artifact);
        } catch (Exception e) {
            log.warn("产物落库/推送失败（已忽略，文件已生成于 OSS）：session={} 原因={}",
                    runContext.sessionId(), e.getMessage());
        }
    }

    /**
     * 从工具结果（JSON 文本）里取出 {@code artifact} 字段。
     * <p>
     * 抽成静态纯函数便于单测（判断"这是不是产物结果"的规则会直接影响
     * 会不会给前端推下载卡片，值得被测试固定）。非产物结果返回 null。
     */
    public static Map<String, Object> extractArtifact(String toolResult) {
        if (toolResult == null || toolResult.isBlank() || !toolResult.contains("\"artifact\"")) {
            // 先做一次廉价的字符串预筛，避免对每一次工具结果都做 JSON 解析
            // （绝大多数工具结果都不是产物，比如 execute_cmd 的大段输出）
            return null;
        }
        try {
            JSONObject json = JSONUtil.parseObj(toolResult);
            JSONObject artifact = json.getJSONObject("artifact");
            return artifact == null ? null : new LinkedHashMap<>(artifact);
        } catch (Exception e) {
            // 不是合法 JSON 或结构不符：当作"没有产物"，不干扰主流程
            return null;
        }
    }

    /**
     * 组装注入系统提示词的「运行时能力」文本（P2-9）。
     * <p>
     * 抽成静态纯函数是为了好测：这些是**给模型看的说明**，措辞会直接影响模型行为 ——
     * 把"不可用"讲清楚，模型才不会反复重试、也不会把"配了但连不上"说成"我没有这个能力"。
     *
     * @param skillsText           技能清单文本（来自 {@code SkillLoader.formatForPrompt}）
     * @param unavailableMcpNames  本次选了但连不上的 MCP 服务名；为空表示无需提示
     */
    public static String composeCapabilities(String skillsText, List<String> unavailableMcpNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("【可用技能】\n")
                .append(skillsText == null || skillsText.isBlank()
                        ? "当前没有可用的技能（skills）。"
                        : skillsText);
        if (unavailableMcpNames != null && !unavailableMcpNames.isEmpty()) {
            sb.append("\n\n【MCP 能力状态】\n")
                    .append("以下 MCP 服务本次连接失败、当前不可用：")
                    .append(String.join("、", unavailableMcpNames))
                    .append("\n它们本次没有被注册，请不要尝试调用；")
                    .append("若用户的任务需要这些能力，如实说明该服务当前不可用，")
                    .append("并建议检查服务地址/凭据或网络后重试。");
        }
        return sb.toString();
    }

    /**
     * 取本次响应的总 token 数（P2-8 记账用）。
     * <p>
     * 有些厂商只返回 in/out 而不给 total，所以 total 为 null 时自己相加；
     * 三项都拿不到则返回 null —— 记账时跳过，**不写 0**（免得统计上把"未知"当成"没消耗"）。
     */
    private static Integer totalTokens(ChatResponse response) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage == null) {
            return null;
        }
        if (usage.totalTokenCount() != null) {
            return usage.totalTokenCount();
        }
        Integer input = usage.inputTokenCount();
        Integer output = usage.outputTokenCount();
        if (input == null && output == null) {
            return null;
        }
        return (input == null ? 0 : input) + (output == null ? 0 : output);
    }

    @Override
    public List<String> getModelList(String baseUrl, String token) {        List<ModelDescription> listModels = OpenAiModelCatalog
                .builder()
                .apiKey(token)
                .baseUrl(baseUrl)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build().listModels();
        return listModels.stream().map(ModelDescription::name).toList();
    }

}
