package com.huzhijian.nexusagentweb.factory;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ModelDTO;
import com.huzhijian.nexusagentweb.em.ModelType;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolRegistry;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import com.huzhijian.nexusagentweb.model.MultimodalTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.Skills;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.HashedMap;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明:
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatContextFactory {
    private final StreamingChatModel defaultModel;
    private final PgChatMemoryStore chatMemoryStore;
    /**
     * 工具不再逐个注入。各工具类实现 {@link AgentToolSet} 自我声明，
     * 由注册表统一收集并按本次运行的选择解析 —— 新增工具无需修改本类。
     * （历史上这里分别注入 BoxTool/LogTool/MemoryTool/RagTool，每加一个都要改工厂，
     * 而且某个字段名还标错了实际类型。）
     */
    private final ToolRegistry toolRegistry;
    private final McpInformationService mcpInformationService;
    private final UserConfigService  userConfigService;
    private final AgentProperties agentProperties;
    private final SkillLoader skillLoader;
    private final ModelCapabilityResolver modelCapabilityResolver;


    public ChatContext create(ChatDTO chatDTO, RunContext runContext){
        Long userId = runContext.userId();
        String sessionId = runContext.sessionId();
//        本次模型的能力（视觉 / 上下文窗口 / 最大输出）：记忆窗口与输出上限都按它算
        ModelCapabilities capabilities = resolveCapabilities(chatDTO.model(), userId);
        StreamingChatModel  model=createModel(chatDTO.model(),userId);
//        MCP：返回「可用的 provider」+「选了但连不上的服务名」（P2-9）。
//        后者会随 ChatContext 传给提示词组装，让模型知道"有这些能力但现在用不了"，
//        而不是只能回一句"我没有这个能力"。
        McpInformationService.McpResolution mcp = mcpInformationService.getMcp(chatDTO.MCPs(),userId);
//        记忆存储绑定本次运行的上下文，必须这样做：
//        LangChain4j 在**流式回调线程**上调用 ChatMemoryStore.updateMessages，
//        那时请求线程的 ThreadLocal 已经取不到值——历史上附件元数据就是这样丢的，
//        userId 也只能靠 Redis 缓存兜底（而那个 key 仅 5 分钟）。
        ChatMemoryStore memoryStore = chatMemoryStore.forRun(runContext);
//        工具由注册表统一解析：常驻工具（沙盒/系统日志/长期记忆）恒启用；
//        按需工具（知识库检索）由各自的 enabled() 依据请求参数决定开关。
//        新增工具只需实现 AgentToolSet 并加 @Component，不必改本类。
        Object[] tools = toolRegistry.resolve(ToolSelection.from(chatDTO)).toArray();
        log.debug("本次注册的工具集：{}", toolRegistry.keys());
        AiServices<ChatAssistant> builder = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .tools(tools)
                .chatMemoryProvider(memoryId -> TokenWindowChatMemory
                        .builder()
//                        记忆窗口：以前是全局写死的 nexus.agent.memory.max-tokens（100000），
//                        与真实模型无关 —— 256k 窗口的模型白白浪费，8k 窗口的模型则被上游拒。
//                        2026-10-03：按「该模型的上下文窗口 − 最大输出」算，再受全局上限兜住。
                        .maxTokens(capabilities.memoryWindow(agentProperties.getMemory().getMaxTokens()),
                                new MultimodalTokenCountEstimator(
                                        agentProperties.getMemory().getTokenEstimatorModel(),
                                        agentProperties.getMemory().getImageTokens()))
                        .chatMemoryStore(memoryStore)
                        .id(sessionId)
                        .build());

//        工具提供者：Skill 与 MCP 都是 ToolProvider。
//        ⚠️ 必须收集到一个集合里用 toolProviders(...) 注册一次 ——
//        连续调用 toolProvider(...) 会相互覆盖，导致只剩最后一个生效。
        List<ToolProvider> toolProviders = new ArrayList<>();
//        Skill：扫描本地目录（见 SkillLoader），请求未指定名称时启用全部
        Skills skills = skillLoader.resolve(chatDTO.skills());
        if (skills != null) {
            toolProviders.add(skills.toolProvider());
        }
        if (mcp.provider() != null) {
            toolProviders.add(mcp.provider());
        }
        if (!toolProviders.isEmpty()) {
            builder.toolProviders(toolProviders);
        }
        ChatAssistant chatAssistant = builder.build();
        return ChatContext.builder().chatAssistant(chatAssistant)
                .sessionId(sessionId)
                .isNewSession(runContext.newSession())
                .mcpUnavailable(mcp.unavailableNames())
                .build();
    }

    /**
     * 解析本次对话**实际会用到**的模型能力（视觉 / 上下文窗口 / 最大输出）。
     * <p>
     * 供 {@code ChatServiceImpl} 在转换用户消息前调用 —— 图片要不要发成真图，
     * 取决于这个模型支不支持视觉。解析不到（用系统默认模型 / 用户没配）时返回默认值。
     */
    public ModelCapabilities resolveCapabilities(ModelDTO modelDTO, Long userId) {
        MatchedModel matched = matchModel(modelDTO, userId);
        if (matched == null) {
            return ModelCapabilities.DEFAULT;
        }
        return ModelCapabilities.of(matched.model());
    }

    /**
     * 一次模型匹配的完整结果：命中的 API 配置 + 命中的模型条目。
     *
     * @param apiConfig 命中的用户 API 配置（含 baseUrl / 加密后的 Key）
     * @param model     该配置里与请求模型名匹配的条目（含视觉 / 窗口 / 输出上限）
     * @param salt      该用户的加密盐值
     * @return null 表示「用系统默认模型」
     */
    private record MatchedModel(APIConfig apiConfig, Model model, String salt) {
    }

    /**
     * 按请求里的模型信息匹配用户配置。
     * <p>
     * 匹配规则与历史实现一致：先用 {@code model.id} 找配置项，没给 id 就用默认配置项；
     * 再在该配置项的模型列表里找「type=CHAT 且名字相同」的条目。
     * 任何一步失败都返回 null（调用方回退系统默认模型），并打日志留痕。
     */
    private MatchedModel matchModel(ModelDTO modelDTO, Long userId) {
        if (modelDTO == null) {
            log.info("回退系统默认模型：用户 {} 的请求未指定模型（model 为空）", userId);
            return null;
        }
        UserConfig userConfig = userConfigService.getUserConfig(userId);
        if (userConfig == null) {
            log.info("回退系统默认模型：用户 {} 没有 API 配置（未配置自带 Key）", userId);
            return null;
        }
        String configJson = String.valueOf(userConfig.getLlmApiToken());
        List<APIConfig> apiConfigs = JSONUtil.toList(configJson, APIConfig.class);
        APIConfig apiConfig = apiConfigs.stream().filter(config -> {
//                如果ID不为空也不为null，那么优先根据id寻找配置，如果为null，那么使用默认配置
            if (modelDTO.id() != null && !modelDTO.id().isEmpty()) {
                return config.getId().equals(modelDTO.id());
            }
            return Boolean.TRUE.equals(config.getIsDefault());
        }).findFirst().orElse(null);

        if (apiConfig == null) {
//              TODO  判断余额是否足够
//              回退本身是预期行为（用户没配就用系统默认），但必须留痕：
//              否则用户会以为在用自己填的 Key，实际走的是系统默认模型
            log.info("回退系统默认模型：用户 {} 的配置里{}，请求模型={}",
                    userId,
                    modelDTO.id() != null && !modelDTO.id().isEmpty()
                            ? "找不到 id=" + modelDTO.id() + " 的配置项"
                            : "没有标记为默认的配置项",
                    modelDTO.modelName());
            return null;
        }
        List<Model> models = apiConfig.getModel() == null ? List.of() : apiConfig.getModel();
        Model matched = models.stream()
                .filter(model -> ModelType.CHAT.equals(model.getType())
                        && modelDTO.modelName().equals(model.getName()))
                .findFirst().orElse(null);
        if (matched == null) {
            log.info("回退系统默认模型：用户 {} 的配置（id={}）中不含可用模型「{}」，已配置的是 {}",
                    userId, apiConfig.getId(), modelDTO.modelName(),
                    models.stream().filter(m -> ModelType.CHAT.equals(m.getType()))
                            .map(Model::getName).toList());
            return null;
        }
        return new MatchedModel(apiConfig, matched, userConfig.getSalt());
    }

    private StreamingChatModel createModel(ModelDTO modelDTO, Long userId) {
        log.debug("模型配置：{}", modelDTO);
        MatchedModel matched = matchModel(modelDTO, userId);
        if (matched == null) {
            return defaultModel;
        }
        APIConfig apiConfig = matched.apiConfig();
        Model model = matched.model();

        String secretApiKey = apiConfig.getAPIKey();
        String apiKey = EncryptorFactory.text(matched.salt()).decrypt(secretApiKey);
//          额外参数按「服务商能力」下发（P2-3）：只发该服务商认的字段，避免 400
        Map<String, Object> extraBody = buildExtraBody(apiConfig.getBaseUrl(), modelDTO);
//          输出上限与上下文窗口来自**该模型的元数据**（2026-10-03），不再是全局写死：
//          用户在配置里填了就按填的来，没填走 32k 默认
        ModelCapabilities capabilities = ModelCapabilities.of(model);
        log.debug("模型 {} 能力：视觉={}，上下文窗口={}，最大输出={}",
                model.getName(), capabilities.vision(),
                capabilities.contextWindow(), capabilities.maxOutputTokens());
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(apiConfig.getBaseUrl())
                .modelName(modelDTO.modelName())
                .maxTokens(capabilities.maxOutputTokens())
                .returnThinking(true)
//                    目前这个配置只针对deepseek
                .sendThinking(true)
                .customParameters(extraBody)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build();
    }

    /**
     * 按服务商能力组装「额外参数」（P2-3）。
     * <p>
     * 只下发该服务商支持的字段：不认某个字段的服务商可能直接 400，而少一个开关只是功能降级，
     * 两者代价不对等。判定依据是 baseUrl（见 {@link ModelCapabilityResolver}）——
     * 同一型号经不同服务商转发时支持的参数并不相同。
     * <p>
     * 用户勾了思考但服务商不支持时会打日志说明，不静默丢弃。
     */
    private Map<String, Object> buildExtraBody(String baseUrl, ModelDTO modelDTO) {
        ModelCapabilityResolver.Capability capability = modelCapabilityResolver.resolve(baseUrl);
        Map<String, Object> extraBody = new HashedMap<>();
        if (capability.thinking()) {
            if (modelDTO.isThinking()) {
                log.debug("开启思考：model={}", modelDTO.modelName());
                extraBody.put("thinking", Map.of("type", "enabled"));
                extraBody.put("enable_thinking", true);
            } else {
                log.debug("关闭思考：model={}", modelDTO.modelName());
                extraBody.put("thinking", Map.of("type", "disabled"));
                extraBody.put("enable_thinking", false);
            }
        } else if (modelDTO.isThinking()) {
            log.info("服务商不支持思考参数，本次已忽略 thinking 开关：baseUrl={} model={}",
                    baseUrl, modelDTO.modelName());
        }
        if (capability.search()) {
            extraBody.put("enable_search", true);
        }
        return extraBody;
    }

}
