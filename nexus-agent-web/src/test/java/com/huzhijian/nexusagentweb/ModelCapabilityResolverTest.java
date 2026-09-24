package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelCapabilityResolver 的纯单元测试（P2-3）：验证「按 baseUrl 判定服务商能力」的核心取舍。
 * <p>
 * 最关键的一条是**未知服务商不下发任何参数** —— 宁可少一个开关，也不要因为多塞字段而 400。
 */
class ModelCapabilityResolverTest {

    private ModelCapabilityResolver withProviders(java.util.Map<String, AgentProperties.ProviderCapability> providers) {
        AgentProperties props = new AgentProperties();
        props.getModel().setProviders(providers);
        ModelCapabilityResolver resolver = new ModelCapabilityResolver(props);
        resolver.init();
        return resolver;
    }

    private AgentProperties.ProviderCapability capability(boolean thinking, boolean search) {
        AgentProperties.ProviderCapability c = new AgentProperties.ProviderCapability();
        c.setThinking(thinking);
        c.setSearch(search);
        return c;
    }

    @Test
    @DisplayName("阿里云百炼：支持思考与联网搜索")
    void dashscopeSupportsBoth() {
        ModelCapabilityResolver.Capability capability =
                new ModelCapabilityResolver(new AgentProperties())
                        .resolve("https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertTrue(capability.thinking());
        assertTrue(capability.search());
    }

    @Test
    @DisplayName("DeepSeek 官方：两个参数都不支持（思考由型号决定）")
    void deepseekSupportsNone() {
        ModelCapabilityResolver.Capability capability =
                new ModelCapabilityResolver(new AgentProperties()).resolve("https://api.deepseek.com");

        assertFalse(capability.thinking());
        assertFalse(capability.search());
    }

    @Test
    @DisplayName("未知服务商默认不下发任何参数（宁可功能降级，也不要 400）")
    void unknownProviderGetsNothing() {
        ModelCapabilityResolver.Capability capability =
                new ModelCapabilityResolver(new AgentProperties())
                        .resolve("https://some-random-gateway.example.com/v1");

        assertFalse(capability.thinking());
        assertFalse(capability.search());
    }

    @Test
    @DisplayName("baseUrl 为空时不抛异常，按不支持处理")
    void blankBaseUrlIsSafe() {
        ModelCapabilityResolver resolver = new ModelCapabilityResolver(new AgentProperties());

        assertFalse(resolver.resolve(null).thinking());
        assertFalse(resolver.resolve("").thinking());
        assertFalse(resolver.resolve("   ").search());
    }

    @Test
    @DisplayName("匹配忽略大小写")
    void matchingIsCaseInsensitive() {
        ModelCapabilityResolver.Capability capability =
                new ModelCapabilityResolver(new AgentProperties())
                        .resolve("https://DashScope.AliYunCS.com/compatible-mode/v1");

        assertTrue(capability.thinking());
    }

    @Test
    @DisplayName("多个片段命中时取最长（更精确）的那条")
    void longestFragmentWins() {
        ModelCapabilityResolver resolver = withProviders(java.util.Map.of(
                // 宽泛片段：只支持 thinking
                "aliyuncs.com", capability(true, false),
                // 精确片段：支持 thinking + search
                "dashscope.aliyuncs.com", capability(true, true)));

        ModelCapabilityResolver.Capability capability =
                resolver.resolve("https://dashscope.aliyuncs.com/compatible-mode/v1");

        assertTrue(capability.search(), "应命中更精确的 dashscope.aliyuncs.com");
    }

    @Test
    @DisplayName("用户配置可覆盖内置（例如给自定义中转站声明能力）")
    void userConfigOverridesBuiltIn() {
        // 内置 deepseek.com 是「都不支持」，这里覆盖成支持思考
        ModelCapabilityResolver resolver = withProviders(java.util.Map.of(
                "deepseek.com", capability(true, false)));

        ModelCapabilityResolver.Capability capability = resolver.resolve("https://api.deepseek.com");

        assertTrue(capability.thinking(), "用户配置应覆盖内置表");
        assertFalse(capability.search());
    }

    @Test
    @DisplayName("用户可补充内置表没有的服务商（自建网关/中转）")
    void userConfigExtendsBuiltIn() {
        ModelCapabilityResolver resolver = withProviders(java.util.Map.of(
                "my-gateway.internal", capability(true, true)));

        ModelCapabilityResolver.Capability capability =
                resolver.resolve("https://my-gateway.internal/v1");

        assertTrue(capability.thinking());
        assertTrue(capability.search());
        // 同时内置项仍然有效
        assertTrue(resolver.resolve("https://dashscope.aliyuncs.com").search());
    }
}
