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
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolRegistry;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
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


    public ChatContext create(ChatDTO chatDTO, RunContext runContext){
        Long userId = runContext.userId();
        String sessionId = runContext.sessionId();
        StreamingChatModel  model=createModel(chatDTO.model(),userId);
        McpToolProvider mcp = mcpInformationService.getMcp(chatDTO.MCPs(),userId);
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
//                        窗口与 token 估算器由 nexus.agent.memory.* 配置。
//                        原实现写死 100000 + gpt-4o，而 gpt-4o 与真实使用的模型无关，裁剪不准。
                        .maxTokens(agentProperties.getMemory().getMaxTokens(),
                                new OpenAiTokenCountEstimator(agentProperties.getMemory().getTokenEstimatorModel()))
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
        if (mcp != null) {
            toolProviders.add(mcp);
        }
        if (!toolProviders.isEmpty()) {
            builder.toolProviders(toolProviders);
        }
        ChatAssistant chatAssistant = builder.build();
        return ChatContext.builder().chatAssistant(chatAssistant)
                .sessionId(sessionId)
                .isNewSession(runContext.newSession())
                .build();
    }

    private StreamingChatModel createModel(ModelDTO modelDTO,Long userId) {
        log.debug("模型配置：{}", modelDTO);
        UserConfig userConfig = userConfigService.getUserConfig(userId);

        if (userConfig!=null&&modelDTO!=null){
//            构造模型
            String configJson = userConfig.getLlmApiToken().toString();
            List<APIConfig> apiConfigs = JSONUtil.toList(configJson, APIConfig.class);
            APIConfig apiConfig = apiConfigs.stream().filter(config -> {
//                如果ID不为空也不为null，那么优先根据id寻找配置，如果为null，那么使用默认配置
                if (modelDTO.id() != null && !modelDTO.id().isEmpty()) {
                    return config.getId().equals(modelDTO.id());
                }
                return config.getIsDefault();
            }).findFirst().orElse(null);


            if (apiConfig==null){
//              TODO  判断余额是否足够
//              回退本身是预期行为（用户没配就用系统默认），但必须留痕：
//              否则用户会以为在用自己填的 Key，实际走的是系统默认模型
                log.info("回退系统默认模型：用户 {} 的配置里{}，请求模型={}",
                        userId,
                        modelDTO.id() != null && !modelDTO.id().isEmpty()
                                ? "找不到 id=" + modelDTO.id() + " 的配置项"
                                : "没有标记为默认的配置项",
                        modelDTO.modelName());
                return defaultModel;
            }
            List<Model> models = apiConfig.getModel();
            boolean match = models.stream().anyMatch(model -> {
//                类型为Chat并且模型名称存在配置中
                return model.getType().equals(ModelType.CHAT) && model.getName().equals(modelDTO.modelName());
            });
            if (!match){
                log.info("回退系统默认模型：用户 {} 的配置（id={}）中不含可用模型「{}」，已配置的是 {}",
                        userId, apiConfig.getId(), modelDTO.modelName(),
                        models.stream().filter(m -> ModelType.CHAT.equals(m.getType()))
                                .map(Model::getName).toList());
                return defaultModel;
            }

            String secretApiKey = apiConfig.getAPIKey();
            String apiKey = EncryptorFactory.text(userConfig.getSalt()).decrypt(secretApiKey);
            Map<String, Object> extraBody = new HashedMap<>();
//          加个customParameters配置,控制是否开启思考
            if (modelDTO.isThinking()){
                log.debug("开启思考");
                extraBody.put("thinking", Map.of("type", "enabled"));
                extraBody.put("enable_thinking", true);
            }else{
                log.debug("不思考");
                extraBody.put("thinking", Map.of("type", "disabled"));
                extraBody.put("enable_thinking", false);
            }
            extraBody.put("enable_search", true);
            return OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiConfig.getBaseUrl())
                    .modelName(modelDTO.modelName())
                    .returnThinking(true)
//                    目前这个配置只针对deepseek
                    .sendThinking(true)
                    .customParameters(extraBody)
                    .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                    .build();
        }
        log.info("回退系统默认模型：{}", userConfig == null
                ? "用户 " + userId + " 没有 API 配置（未配置自带 Key）"
                : "请求未指定模型（model 为空）");
        return defaultModel;
    }


}
