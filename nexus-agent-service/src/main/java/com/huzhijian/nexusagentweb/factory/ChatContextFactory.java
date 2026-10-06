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
import com.huzhijian.nexusagentweb.model.ChatModelFactory;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.model.SystemModelRegistry;
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
import org.springframework.beans.factory.ObjectProvider;
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
    /**
     * langchain4j starter 根据 {@code langchain4j.open-ai.streaming-chat-model} 建的那个模型。
     * <p>
     * 用 {@code ObjectProvider} 而不是直接注入：<b>它是可选的</b>。
     * 以前直接注入意味着 yml 里那段（含 {@code api-key: ${DEEPSEEK}}）**必须存在**，
     * 否则应用启动就失败 —— 于是"我不想用 DeepSeek 了"这件事根本做不到：
     * 就算配了 {@code nexus.agent.system-models}，DeepSeek 的配置还是得原样留着。
     * 现在改成可选：配了 system-models 就完全可以不配 langchain4j 那段。
     */
    private final ObjectProvider<StreamingChatModel> defaultModelProvider;
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
    private final ChatModelFactory chatModelFactory;
    /** 系统内置模型（多供应商）；未配置时为空，走 langchain4j starter 的单一默认模型 */
    private final SystemModelRegistry systemModelRegistry;


    public ChatContext create(ChatDTO chatDTO, RunContext runContext){
        long t0 = System.nanoTime();
        Long userId = runContext.userId();
        String sessionId = runContext.sessionId();
//        本次模型的能力（视觉 / 上下文窗口 / 最大输出）：记忆窗口与输出上限都按它算
//        ⚠️ 2026-10-05：模型匹配**只做一次**。以前 resolveCapabilities 与 createModel
//        各自调一次 matchModel（每次要查 user_config），同一次请求里白白多一轮查询；
//        现在算一次，两处共用。
        MatchedModel matched = matchModel(chatDTO.model(), userId);
        ModelCapabilities capabilities = matched != null
                ? ModelCapabilities.of(matched.model())
                : systemModelRegistry.resolveCapabilities(chatDTO.model());
        StreamingChatModel model = createModel(chatDTO.model(), matched);
        long t1 = System.nanoTime();
//        MCP：返回「可用的 provider」+「选了但连不上的服务名」（P2-9）。
//        后者会随 ChatContext 传给提示词组装，让模型知道"有这些能力但现在用不了"，
//        而不是只能回一句"我没有这个能力"。
        McpInformationService.McpResolution mcp = mcpInformationService.getMcp(chatDTO.MCPs(),userId);
        long t2 = System.nanoTime();
//        记忆存储绑定本次运行的上下文，必须这样做：
//        LangChain4j 在**流式回调线程**上调用 ChatMemoryStore.updateMessages，
//        那时请求线程的 ThreadLocal 已经取不到值——历史上附件元数据就是这样丢的，
//        userId 也只能靠 Redis 缓存兜底（而那个 key 仅 5 分钟）。
        ChatMemoryStore memoryStore = chatMemoryStore.forRun(runContext);
//        工具由注册表统一解析：常驻工具（沙盒/系统日志/长期记忆）恒启用；
//        按需工具（知识库检索）由各自的 enabled() 依据请求参数决定开关。
//        新增工具只需实现 AgentToolSet 并加 @Component，不必改本类。
        int memoryWindow = capabilities.memoryWindow(agentProperties.getMemory().getMaxTokens());
        Object[] tools = toolRegistry.resolve(ToolSelection.from(chatDTO)).toArray();
        log.debug("本次注册的工具集：{}", toolRegistry.keys());
//        🔴 2026-10-06：tools 与 skills 拆开计时。
//        线上实测这两段合计 1293ms，占 preflight（1318ms）的 98% —— 是首字延迟的最大单项。
//        但合成一个数字时无法判断是「工具注册慢」还是「技能解析慢」，而两者的修法完全不同：
//          · 慢在 tools  → ToolSpecification 生成（反射 + JSON Schema）
//          · 慢在 skills → 目录扫描 + 查 user_skill 表
//        ⚠️ 首次调用还含 JIT 与 BPE 词表加载（冷启动），所以要靠「第二次是否降下来」判断真假。
        long tTools = System.nanoTime();
        AiServices<ChatAssistant> builder = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .tools(tools)
                .chatMemoryProvider(memoryId -> TokenWindowChatMemory
                        .builder()
//                        记忆窗口：以前是全局写死的 nexus.agent.memory.max-tokens（100000），
//                        与真实模型无关 —— 256k 窗口的模型白白浪费，8k 窗口的模型则被上游拒。
//                        2026-10-03：按「该模型的上下文窗口 − 最大输出」算，再受全局上限兜住。
                        .maxTokens(memoryWindow,
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
//        Skill：官方（部署目录）+ 该用户的（上传/AI 生成的），请求未指定名称时启用全部
//        ⚠️ 2026-10-05：技能清单**只解析一次**。以前这里 resolve 一次，
//        ChatServiceImpl 组装提示词时又调一次 skillLoader.formatForPrompt（内部再 resolve 一次），
//        等于每次对话多查一遍用户技能表。现在解析一次，把结果随 ChatContext 带出去复用。
        long tSkills = System.nanoTime();
        Skills skills = skillLoader.resolve(chatDTO.skills(), userId);
        long tSkillResolved = System.nanoTime();
//        🔴 2026-10-06 第二次修正埋点：上一版把 skills.toolProvider() 与 mcp.provider()
//        都算进了「aiServices+bind」，于是线上 1141ms 全落在那一栏里，无法定位。
//        而 skills.toolProvider() 要**为每个技能生成一份 ToolSpecification** ——
//        技能多时它是随数量线性增长的，而 resolve() 只是查库/扫目录（本地实测 3ms）。
//        这就是「数值吻合但结论错误」的又一轮：本地 build() 只有 3ms，线上 1141ms。
        dev.langchain4j.service.tool.ToolProvider skillTp = null;
        if (skills != null) {
            skillTp = skills.toolProvider();
        }
        long tSkillTp = System.nanoTime();
        dev.langchain4j.service.tool.ToolProvider mcpTp = mcp.provider();
        long tMcpTp = System.nanoTime();
        if (skillTp != null) {
            toolProviders.add(skillTp);
        }
        if (mcpTp != null) {
            toolProviders.add(mcpTp);
        }
        if (!toolProviders.isEmpty()) {
            builder.toolProviders(toolProviders);
        }
        long tBind = System.nanoTime();
        ChatAssistant chatAssistant = builder.build();
        long t3 = System.nanoTime();

//        🔴 CHAT_DECISION（2026-10-06，用户要求「显式化默认行为」的第二条）：
//        把**本次请求的每一个决策**集中打一行，回答那些只能靠猜的问题：
//          · 「为什么模型用了这个模型」 → 模型来源（用户自带 / 系统默认）与回退原因
//          · 「为什么加载了这些技能」     → 请求没传 vs 明确指定 vs 明确禁用
//          · 「为什么这个工具没加载」     → enabled() 的判定结果与理由
//        以前这些判断散落在 create() 的各个 if 分支里，出问题只能顺着代码读；
//        现在一行日志就能对账。⚠️ 它回答的是「决策」，不是「耗时」——耗时看 CHAT_CONTEXT。
        logRequestDecisions(runContext, chatDTO, matched, capabilities, tools, skillTp != null, mcpTp != null,
                memoryWindow);

//        🔴 window（记忆窗口）是首字延迟的**决定性参数**：它就是本次最多会带多少 token 的历史
//        给模型，模型的 prefill 量与它成正比。看到首字慢，先拿这一行和 CHAT_MEMORY 的 msgs 对照。
//        tools 数量也在这里 —— 15 个工具的 JSON Schema 每轮都要跟着请求发出去。
        log.info("CHAT_CONTEXT runId={} match+model={}ms mcp={}ms tools={}ms skillResolve={}ms"
                        + " skillToolProvider={}ms(skillN={}) mcpToolProvider={}ms bind={}ms build={}ms"
                        + " | total={}ms window={} ctx={} out={} toolN={} mcpOn={}",
                runContext.runId(), ms(t0, t1), ms(t1, t2), ms(t2, tTools),
                ms(tSkills, tSkillResolved), ms(tSkillResolved, tSkillTp), skillCount(skillLoader, userId),
                ms(tSkillTp, tMcpTp), ms(tMcpTp, tBind), ms(tBind, t3), ms(t0, t3),
                memoryWindow, capabilities.contextWindow(), capabilities.maxOutputTokens(),
                tools.length, mcpTp != null);
        return ChatContext.builder().chatAssistant(chatAssistant)
                .sessionId(sessionId)
                .isNewSession(runContext.newSession())
                .mcpUnavailable(mcp.unavailableNames())
                .skills(skills)
                .skillsText(skillLoader.formatResolved(skills))
                .build();
    }

    /** 两个 nanoTime 之间的毫秒数（分段耗时埋点用） */
    /**
     * 打印本次请求的决策快照（一行，字段用 {@code |} 分隔便于 grep）。
     * <p>
     * 刻意<b>不</b>打「请求里传了什么原始值」—— 那属于回显、且可能含用户输入；
     * 这里只打<b>后端据此做出的决定</b>，因为排查时需要的是后者。
     */
    private void logRequestDecisions(RunContext runContext, ChatDTO chatDTO,
                                     MatchedModel matched, ModelCapabilities capabilities,
                                     Object[] tools, boolean skillsOn, boolean mcpOn, int memoryWindow) {
        // 技能：三种语义各不相同，日志里必须能分辨（null=启用全部 / []=全禁 / 有值=只启用这些）
        String skillDecision;
        List<String> requested = chatDTO.skills();
        if (requested == null) {
            skillDecision = "全部";
        } else if (requested.isEmpty()) {
            skillDecision = "已全部禁用";
        } else {
            skillDecision = "仅指定(" + requested.size() + "个)";
        }
        // 模型：用户自带 vs 系统默认，以及为什么回退
        String modelDecision = matched != null
                ? "用户自带:" + matched.model().getName()
                : "系统默认(用户未配置该模型)" ;
        // ⚠️ 这里曾想打「能力元数据 degraded」—— ModelCapabilities 没有这个概念
        // （degraded 是 QuotaVO 的字段，用于配额查询失败时的降级标记）。别再混淆。
        log.info("CHAT_DECISION runId={} 模型={} 技能={} 知识库={} MCP={} 工具={}个 记忆窗口={}token 输出上限={}",
                runContext.runId(), modelDecision, skillDecision,
                chatDTO.enableLexiangRag() ? "乐享" : "关",
                mcpOn ? "开" : "关",
                tools.length, memoryWindow, capabilities.maxOutputTokens());
    }

    /**
     * 本次可用的技能数量（打进日志）。
     * <p>
     * 用途：判断 {@code skillToolProvider} 的耗时是否<b>随技能数线性增长</b> ——
     * {@code toolProvider()} 要为每个技能生成一份 ToolSpecification（工具名、描述、参数 schema），
     * 而 {@code resolve()} 只是查库/扫目录，两者量级完全不同，混在一起就看不出瓶颈在哪。
     * <p>
     * ⚠️ 走 {@code availableNames} 而不是从 {@code Skills} 对象上取：
     * {@code Skills}（langchain4j-skills 1.12.1-beta21）只暴露 {@code toolProvider()}
     * 与 {@code formatAvailableSkills()}，没有取列表的方法；而 {@code availableNames}
     * 走 SkillLoader 自己的 TTL 缓存，重复调用几乎零成本。
     * <p>
     * 取不到时返回 <b>-1</b> 而不是 0：0 会被读成「一个技能都没有」，
     * 而实际可能是查询失败 —— 两者排查方向完全不同。
     */
    private static int skillCount(SkillLoader skillLoader, Long userId) {
        try {
            return skillLoader.availableNames(userId).size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long ms(long from, long to) {
        return (to - from) / 1_000_000L;
    }

    /**
     * 解析本次对话**实际会用到**的模型能力（视觉 / 上下文窗口 / 最大输出）。
     * <p>
     * 供 {@code ChatServiceImpl} 在转换用户消息前调用 —— 图片要不要发成真图，
     * 取决于这个模型支不支持视觉。
     * <p>
     * 优先用用户自带配置里的模型元数据；没有自带配置时用**系统内置模型**的元数据。
     */
    public ModelCapabilities resolveCapabilities(ModelDTO modelDTO, Long userId) {
        MatchedModel matched = matchModel(modelDTO, userId);
        if (matched != null) {
            return ModelCapabilities.of(matched.model());
        }
        return systemModelRegistry.resolveCapabilities(modelDTO);
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

    /**
     * 用「已经匹配过」的结果建模型，避免同一次请求重复查库匹配。
     *
     * @param matched 本次请求的模型匹配结果；<b>null 表示走系统内置 / 兜底模型</b>
     */
    private StreamingChatModel createModel(ModelDTO modelDTO, MatchedModel matched) {
        log.debug("模型配置：{}", modelDTO);
        if (matched == null) {
//            用户没配自带 Key（或没匹配上）→ 系统内置模型；再没有才用 langchain4j 的单一默认
            StreamingChatModel systemModel = systemModelRegistry.resolveModel(modelDTO);
            if (systemModel != null) {
                log.debug("使用系统内置模型：请求模型={}", modelDTO == null ? "(未指定)" : modelDTO.modelName());
                return systemModel;
            }
            StreamingChatModel fallback = defaultModelProvider.getIfAvailable();
            if (fallback == null) {
//                既没有系统内置模型、也没有 langchain4j 那段配置 —— 这是配置错误，要明确说清
                throw new IllegalStateException(
                        "没有任何可用的对话模型：nexus.agent.system-models 未配置（或配置不完整被跳过），"
                                + "且 langchain4j.open-ai.streaming-chat-model 也没配。"
                                + "请至少配置其中一个。");
            }
            return fallback;
        }
        APIConfig apiConfig = matched.apiConfig();
        Model model = matched.model();

        String secretApiKey = apiConfig.getAPIKey();
        String apiKey = EncryptorFactory.text(matched.salt()).decrypt(secretApiKey);
//          输出上限与上下文窗口来自**该模型的元数据**（2026-10-03），不再是全局写死：
//          用户在配置里填了就按填的来，没填走 32k 默认
        ModelCapabilities capabilities = ModelCapabilities.of(model);
        log.debug("模型 {} 能力：视觉={}，上下文窗口={}，最大输出={}",
                model.getName(), capabilities.vision(),
                capabilities.contextWindow(), capabilities.maxOutputTokens());
        return chatModelFactory.build(apiConfig.getBaseUrl(), apiKey, modelDTO.modelName(),
                capabilities.maxOutputTokens(), modelDTO.isThinking());
    }

}
