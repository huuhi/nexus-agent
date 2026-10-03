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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemModelRegistry} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 新增的「系统内置模型列表（多供应商）」：
 * 以前系统默认模型只有 yml 里那一个 Bean，加第二个或换供应商都得改配置结构。
 */
@DisplayName("SystemModelRegistry —— 多供应商系统模型的选择与兜底")
class SystemModelRegistryTest {

    private static AgentProperties.SystemModel model(String id, String modelName, String baseUrl,
                                                     Boolean vision, Integer contextWindow) {
        AgentProperties.SystemModel m = new AgentProperties.SystemModel();
        m.setId(id);
        m.setName("展示名-" + id);
        m.setModelName(modelName);
        m.setBaseUrl(baseUrl);
        m.setApiKey("sk-test-" + id);
        m.setVision(vision);
        m.setContextWindow(contextWindow);
        return m;
    }

    private SystemModelRegistry registry(List<AgentProperties.SystemModel> models) {
        AgentProperties props = new AgentProperties();
        props.setSystemModels(models);
        ChatModelFactory factory = new ChatModelFactory(new ModelCapabilityResolver(props));
        SystemModelRegistry registry = new SystemModelRegistry(props, factory);
        registry.init();
        return registry;
    }

    private SystemModelRegistry twoModels() {
        return registry(List.of(
                model("deepseek", "deepseek-chat", "https://api.deepseek.com", false, 131_072),
                model("qwen", "qwen3-max", "https://dashscope.aliyuncs.com/compatible-mode/v1", true, 262_144)));
    }

    @Test
    @DisplayName("按 id 精确选中对应供应商的模型")
    void resolveById() {
        SystemModelRegistry registry = twoModels();
        StreamingChatModel m1 = registry.resolveModel(new ModelDTO("deepseek", "deepseek-chat", false));
        StreamingChatModel m2 = registry.resolveModel(new ModelDTO("qwen", "qwen3-max", false));
        assertNotSame(m1, m2, "不同供应商应该是不同的模型实例");
    }

    @Test
    @DisplayName("只有 modelName 没有 id：按模型名匹配")
    void resolveByModelName() {
        SystemModelRegistry registry = twoModels();
        StreamingChatModel byName = registry.resolveModel(new ModelDTO(null, "qwen3-max", false));
        StreamingChatModel byId = registry.resolveModel(new ModelDTO("qwen", "qwen3-max", false));
        assertSame(byId, byName);
    }

    @Test
    @DisplayName("匹配不上时回退第一个（系统模型本来就是兜底用的）")
    void fallBackToFirst() {
        SystemModelRegistry registry = twoModels();
        StreamingChatModel fallback = registry.resolveModel(new ModelDTO("不存在的id", "不存在的模型", false));
        assertSame(registry.resolveModel(new ModelDTO("deepseek", "deepseek-chat", false)), fallback);
    }

    @Test
    @DisplayName("能力元数据来自该项配置：qwen 支持视觉、窗口 262144")
    void capabilitiesFromConfig() {
        SystemModelRegistry registry = twoModels();

        ModelCapabilities qwen = registry.resolveCapabilities(new ModelDTO("qwen", "qwen3-max", false));
        assertTrue(qwen.vision());
        assertEquals(262_144, qwen.contextWindow());

        ModelCapabilities deepseek = registry.resolveCapabilities(new ModelDTO("deepseek", "deepseek-chat", false));
        assertTrue(!deepseek.vision(), "没填 vision 就该是 false");
        assertEquals(131_072, deepseek.contextWindow());
    }

    @Test
    @DisplayName("未配置（空列表）：isEmpty 为真，解析返回空/默认，老行为不变")
    void emptyConfigKeepsOldBehaviour() {
        SystemModelRegistry registry = registry(List.of());
        assertTrue(registry.isEmpty());
        assertNull(registry.resolveModel(new ModelDTO("deepseek", "deepseek-chat", false)));
        assertEquals(ModelCapabilities.DEFAULT, registry.resolveCapabilities(null));
    }

    @Test
    @DisplayName("配置缺字段（没 apiKey）的那项被跳过，不影响其它项")
    void incompleteEntryIsSkipped() {
        AgentProperties.SystemModel broken = model("broken", "x", "https://api.deepseek.com", null, null);
        broken.setApiKey("");
        SystemModelRegistry registry = registry(List.of(
                broken,
                model("ok", "ok-model", "https://api.deepseek.com", false, null)));

        assertTrue(registry.getDefinitions().stream().noneMatch(d -> "broken".equals(d.getId())));
        assertEquals(1, registry.getDefinitions().size());
    }
}
