package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.dto.ModelDTO;
import com.huzhijian.nexusagentweb.model.ChatModelFactory;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.model.SystemModelRegistry;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemModelRegistry} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 的「系统内置模型：多供应商 + 每家可挂多个模型」：
 * 以前系统默认模型只有 yml 里那一个 Bean，加第二个或换供应商都得改配置结构。
 */
@DisplayName("SystemModelRegistry —— 多供应商、每家多个模型")
class SystemModelRegistryTest {

    private static final String DEEPSEEK_URL = "https://api.deepseek.com";
    private static final String QWEN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 模型条目 */
    private static AgentProperties.SystemModel.ModelEntry modelEntry(String id, String modelName) {
        AgentProperties.SystemModel.ModelEntry m = new AgentProperties.SystemModel.ModelEntry();
        m.setId(id);
        m.setModelName(modelName);
        return m;
    }

    private static AgentProperties.SystemModel.ModelEntry visionEntry(String id, String modelName) {
        AgentProperties.SystemModel.ModelEntry m = modelEntry(id, modelName);
        m.setVision(true);
        return m;
    }

    /** 供应商（含多个模型） */
    private static AgentProperties.SystemModel provider(String id, String baseUrl,
                                                        List<AgentProperties.SystemModel.ModelEntry> models) {
        AgentProperties.SystemModel p = new AgentProperties.SystemModel();
        p.setId(id);
        p.setName("供应商-" + id);
        p.setBaseUrl(baseUrl);
        p.setApiKey("sk-test-" + id);
        p.setModels(models);
        return p;
    }

    private SystemModelRegistry registry(List<AgentProperties.SystemModel> providers) {
        AgentProperties props = new AgentProperties();
        props.setSystemModels(providers);
        ChatModelFactory factory = new ChatModelFactory(new ModelCapabilityResolver(props));
        SystemModelRegistry registry = new SystemModelRegistry(props, factory);
        registry.init();
        return registry;
    }

    /** 两家供应商：DeepSeek 两个模型，百炼两个模型（其中一个支持视觉） */
    private SystemModelRegistry twoProviders() {
        return registry(List.of(
                provider("deepseek", DEEPSEEK_URL, List.of(
                        modelEntry("deepseek-chat", "deepseek-chat"),
                        modelEntry("deepseek-reasoner", "deepseek-reasoner"))),
                provider("qwen", QWEN_URL, List.of(
                        modelEntry("qwen3-max", "qwen3-max"),
                        visionEntry("qwen-vl", "qwen-vl-max")))));
    }

    @Test
    @DisplayName("一家供应商的多个模型都装载进来（共用 baseUrl/apiKey，只写一次）")
    void oneProviderMultipleModels() {
        SystemModelRegistry registry = twoProviders();
        assertEquals(4, registry.getEntries().size(), "两家各两个 = 4 个模型");
        assertNotNull(registry.resolveModel(new ModelDTO("deepseek-chat", "deepseek-chat", false)));
        assertNotNull(registry.resolveModel(new ModelDTO("deepseek-reasoner", "deepseek-reasoner", false)));
    }

    @Test
    @DisplayName("同一供应商的不同模型是不同实例")
    void differentModelsAreDifferentInstances() {
        SystemModelRegistry registry = twoProviders();
        StreamingChatModel chat = registry.resolveModel(new ModelDTO("deepseek-chat", "deepseek-chat", false));
        StreamingChatModel reasoner = registry.resolveModel(new ModelDTO("deepseek-reasoner", "deepseek-reasoner", false));
        assertNotSame(chat, reasoner);
    }

    @Test
    @DisplayName("视觉能力按模型区分：同一家里只有 qwen-vl 支持看图")
    void visionIsPerModel() {
        SystemModelRegistry registry = twoProviders();

        assertTrue(registry.resolveCapabilities(new ModelDTO("qwen-vl", "qwen-vl-max", false)).vision());
        assertFalse(registry.resolveCapabilities(new ModelDTO("qwen3-max", "qwen3-max", false)).vision(),
                "同一供应商的其它模型不该被带成支持视觉");
        assertFalse(registry.resolveCapabilities(new ModelDTO("deepseek-chat", "deepseek-chat", false)).vision());
    }

    @Test
    @DisplayName("只传 modelName 没有 id：也能匹配到")
    void resolveByModelName() {
        SystemModelRegistry registry = twoProviders();
        assertSame(registry.resolveModel(new ModelDTO("qwen-vl", "qwen-vl-max", false)),
                registry.resolveModel(new ModelDTO(null, "qwen-vl-max", false)));
    }

    @Test
    @DisplayName("模型级没填的字段继承供应商级")
    void inheritFromProvider() {
        AgentProperties.SystemModel p = provider("p", QWEN_URL, List.of(modelEntry("m1", "model-1")));
        p.setVision(true);
        p.setContextWindow(100_000);
        SystemModelRegistry registry = registry(List.of(p));

        ModelCapabilities caps = registry.resolveCapabilities(new ModelDTO("m1", "model-1", false));
        assertTrue(caps.vision(), "模型级没填 → 用供应商级的 true");
        assertEquals(100_000, caps.contextWindow());
    }

    @Test
    @DisplayName("模型不写 id/name 时：id 用 modelName，绝不能回退成供应商 id（否则同家模型互相覆盖）")
    void idFallsBackToModelNameNotProviderId() {
        SystemModelRegistry registry = registry(List.of(
                provider("deepseek", DEEPSEEK_URL, List.of(
                        newEntry("deepseek-chat"),
                        newEntry("deepseek-reasoner")))));

        assertEquals(2, registry.getEntries().size(), "两个模型都得在，不能被互相覆盖");
        assertEquals("deepseek-chat", registry.getEntries().get(0).id());
        assertEquals("deepseek-reasoner", registry.getEntries().get(1).id());
//        展示名默认是模型名；供应商名只体现在 providerName 上
        assertEquals("deepseek-chat", registry.getEntries().get(0).name());
        assertEquals("deepseek", registry.getEntries().get(0).providerId());
    }

    /** 只写 modelName 的条目（最简写法） */
    private static AgentProperties.SystemModel.ModelEntry newEntry(String modelName) {
        AgentProperties.SystemModel.ModelEntry m = new AgentProperties.SystemModel.ModelEntry();
        m.setModelName(modelName);
        return m;
    }

    @Test
    @DisplayName("兼容旧写法：不写 models，只在供应商上写 modelName")
    void legacySingleModelSyntax() {
        AgentProperties.SystemModel p = provider("legacy", DEEPSEEK_URL, List.of());
        p.setModelName("deepseek-chat");
        SystemModelRegistry registry = registry(List.of(p));

        assertEquals(1, registry.getEntries().size());
        assertSame(registry.resolveModel(new ModelDTO("legacy", "deepseek-chat", false)),
                registry.resolveModel(new ModelDTO(null, "deepseek-chat", false)));
    }

    @Test
    @DisplayName("匹配不上时回退第一个（系统模型本来就是兜底用的）")
    void fallBackToFirst() {
        SystemModelRegistry registry = twoProviders();
        assertSame(registry.resolveModel(new ModelDTO("deepseek-chat", "deepseek-chat", false)),
                registry.resolveModel(new ModelDTO("不存在", "不存在", false)));
    }

    @Test
    @DisplayName("配置不完整（缺 apiKey / 缺 modelName）的项被跳过，不影响其它项")
    void incompleteEntriesAreSkipped() {
        AgentProperties.SystemModel noKey = provider("nokey", DEEPSEEK_URL, List.of(modelEntry("x", "x-model")));
        noKey.setApiKey("");
        AgentProperties.SystemModel noModelName = provider("nomodel", DEEPSEEK_URL, List.of(modelEntry("y", "")));

        SystemModelRegistry registry = registry(List.of(noKey, noModelName,
                provider("ok", DEEPSEEK_URL, List.of(modelEntry("ok-model", "ok-model")))));

        assertEquals(1, registry.getEntries().size());
        assertEquals("ok-model", registry.getEntries().get(0).id());
    }

    @Test
    @DisplayName("未配置（空列表）：isEmpty 为真，老行为不变")
    void emptyConfigKeepsOldBehaviour() {
        SystemModelRegistry registry = registry(List.of());
        assertTrue(registry.isEmpty());
        assertNull(registry.resolveModel(new ModelDTO("deepseek-chat", "deepseek-chat", false)));
        assertEquals(ModelCapabilities.DEFAULT, registry.resolveCapabilities(null));
    }
}
