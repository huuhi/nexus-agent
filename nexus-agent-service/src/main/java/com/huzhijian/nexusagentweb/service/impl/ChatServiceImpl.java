package com.huzhijian.nexusagentweb.service.impl;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.ChatContextFactory;
import com.huzhijian.nexusagentweb.observability.RunMetrics;
import com.huzhijian.nexusagentweb.observability.RunMetricsReporter;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
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

    @Override
    public SseEmitter chat(ChatDTO chatDTO) {

        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录!");
        }
//        超时由 nexus.agent.sse.timeout 配置（默认 120 秒），必须大于最慢一次模型调用的耗时
        SseEmitter sseEmitter = new SseEmitter(agentProperties.getSse().getTimeout().toMillis());

        List<ChatUserMessage> messages = chatDTO.messages();

//        1) 先转换用户消息：附件元数据由返回值带回，不再写 ThreadLocal
        ChatMessageConverter.ConvertedMessage converted;
        try {
            converted = converter.toContents(messages);
        } catch (ClientException e) {
            throw new ValidationException("参数错误!");
        } catch (IOException e) {
            throw new ParserFileException("解析文件失败!");
        }

//        2) 组装本次运行上下文。后续流式回调运行在线程池里，
//        用户ID 与附件元数据只能通过这个对象带过去（ThreadLocal 在那里取不到值）
        String incomingSessionId = chatDTO.sessionId();
        boolean isNewSession = incomingSessionId == null || incomingSessionId.isEmpty();
        String sessionId = isNewSession ? UUID.randomUUID().toString() : incomingSessionId;
        RunContext runContext = new RunContext(userId, sessionId, isNewSession, converted.metadata());

//        trace_id：贯穿一次 Run 的日志与 SSE 事件，把「用户看到的报错」与「服务端日志」对上
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
//        本次运行的指标累加器（token / 工具调用 / 耗时），结尾汇总成一行 RUN 日志（见 §6.12）
        RunMetrics metrics = new RunMetrics(runId, sessionId, userId);

//        3) 构建对话上下文（内部会把 runContext 绑定到记忆存储上）
        ChatContext chatContext = chatContextFactory.create(chatDTO, runContext);
        ChatAssistant chatAssistant = chatContext.getChatAssistant();

//        技能清单必须在调用前注入系统提示词：@SystemMessage 是静态文本，
//        而「有哪些技能」取决于 skills 目录与请求参数，只能通过 Mustache 变量传入
        String availableSkills = skillLoader.formatForPrompt(chatDTO.skills());
        log.debug("注入提示词的技能清单：{}", availableSkills);
        TokenStream tokenStream = chatAssistant.chat(converted.contents(), sessionId, availableSkills);

        SseResponseConverter writer = SseResponseConverter.builder().chatHistoryListService(chatHistoryListService)
                .sessionId(sessionId)
                .isNewSession(isNewSession)
                .runId(runId)
                .message(converter.extractFirstText(messages)).userId(userId)
                .sseEmitter(sseEmitter)
                .build();

        sseEmitter.onCompletion(writer::finish);
        sseEmitter.onTimeout(()->writer.onError(new Throwable("超时！")));
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
                })
                .onCompleteResponse(response -> {
//                    token 用量与真实模型名只有在这里拿得到（流式响应的最后一次回调）
                    metrics.recordResponse(response);
                    runMetricsReporter.report(metrics);
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

    @Override
    public List<String> getModelList(String baseUrl, String token) {
        List<ModelDescription> listModels = OpenAiModelCatalog
                .builder()
                .apiKey(token)
                .baseUrl(baseUrl)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build().listModels();
        return listModels.stream().map(ModelDescription::name).toList();
    }

}
