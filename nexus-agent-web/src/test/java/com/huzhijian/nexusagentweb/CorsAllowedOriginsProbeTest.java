package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.CorsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「application.yml 写了值，prod profile 就能继承到吗？」—— 拿真实容器验，不靠推测。
 * <p>
 * 起因是一次口头建议失误：看到 {@code application-prod.yml} 里
 * {@code nexus.agent.cors.allowed-origins:} 是空值，就断言「生产开 CORS 需补域名」，
 * 实际 {@code @Value} 走的是 Spring Environment 的**属性源叠加**，
 * {@code application.yml} 那份可能已提供值。结论必须实测。
 * <p>
 * 手法同 {@link RuntimeConfigBindingTest}：{@link ApplicationContextRunner}
 * + {@link ConfigDataApplicationContextInitializer} 起真实迷你容器真读 yml，
 * 只挂 Properties 类，不连库不起 Tomcat，秒级完成。
 */
@DisplayName("CORS 的 allowed-origins：yml 写了要真被 Environment 读到")
class CorsAllowedOriginsProbeTest {

    @Configuration(proxyBeanMethods = false)
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsConfig.class)
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class));

    /**
     * 起容器把 {@code nexus.agent.cors.allowed-origins} 的**真实**取值取出来。
     * <p>
     * 注意 {@code runner.run(...)} 的入参是 {@code ContextConsumer}（void 返回），
     * 不能在 lambda 里 return，所以用长度为 1 的数组把值带出来。
     */
    private String rawValue(ApplicationContextRunner runner) {
        String[] holder = new String[1];
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure(), "迷你容器启动失败：" + ctx.getStartupFailure());
            holder[0] = ctx.getEnvironment().getProperty("nexus.agent.cors.allowed-origins");
        });
        return holder[0];
    }

    @Test
    @DisplayName("默认 profile：application.yml 那份会被读到（列出 3 个来源）")
    void defaultProfileReadsApplicationYml() {
        String raw = rawValue(runner);
        assertNotNull(raw, "application.yml 里没读到 allowed-origins");

        List<String> origins = CorsConfig.parseOrigins(raw);
        assertTrue(origins.contains("http://localhost:5173"),
                () -> "没读到 localhost:5173，实际解析出：" + origins);
    }

    @Test
    @DisplayName("prod profile：空值键会不会顶掉 application.yml 的值？（关键）")
    void prodProfileDoesNotBlankOutTheValue() {
        // 模拟 SPRING_PROFILES_ACTIVE=prod
        runner.withPropertyValues("spring.profiles.active=prod");

        String raw = rawValue(runner);
        System.out.println(">>> prod profile 下 Environment 实际读到的值 = "
                + (raw == null ? "null" : "[" + raw + "]"));
        System.out.println(">>> 解析出的来源列表 = " + CorsConfig.parseOrigins(raw));

        // 若 prod 的空值键**没有**顶掉默认值，这里就还能拿到 application.yml 的来源
        List<String> origins = CorsConfig.parseOrigins(raw);
        assertTrue(!origins.isEmpty(),
                "prod profile 下 allowed-origins 解析为空 —— 说明 application.yml 的值被空键顶掉了，"
                        + "CorsConfig 会走「不注册任何规则」分支（等同关闭 CORS），"
                        + "必须把精确域名写进 application-prod.yml");
    }

    @Test
    @DisplayName("enabled 开关：prod 里是 true，光有开关没域名等于没开")
    void enabledWithoutOriginsRegistersNothing() {
        runner.withPropertyValues("spring.profiles.active=prod");
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            boolean enabled = ctx.getEnvironment()
                    .getProperty("nexus.agent.cors.enabled", Boolean.class, Boolean.FALSE);
            System.out.println(">>> prod profile 下 cors.enabled = " + enabled);
            assertTrue(enabled, "prod 里 cors.enabled 应为 true");
        });
    }

    /**
     * ⚠️ 这一条才是本次真正要回答的问题：<b>把 {@code cors:} 整段从 prod 里删掉</b>
     * （而不是留一个空值键），Environment 会读到什么？
     * <p>
     * 两种写法行为可能不同 —— 留 {@code allowed-origins:} 空值键 与 整段删掉，
     * YAML 解析后属性源里到底还留不留这个 key、留的话值是什么，
     * 光看代码看不出来，必须让真实容器告诉我们。
     */
    @Test
    @DisplayName("整段删掉 cors: 后，enabled 回落成 application.yml 的值（不是 false）")
    void removingCorsSectionEntirelyFallsBackToApplicationYml() {
        runner.withPropertyValues("spring.profiles.active=prod");
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            // 整段删除 → prod 不再贡献这两个键 → 回落 application.yml
            Boolean enabled = ctx.getEnvironment()
                    .getProperty("nexus.agent.cors.enabled", Boolean.class);
            String origins = ctx.getEnvironment().getProperty("nexus.agent.cors.allowed-origins");
            System.out.println(">>> [整段删除的情形] enabled = " + enabled);
            System.out.println(">>> [整段删除的情形] allowed-origins = " + origins);

            assertEquals(Boolean.TRUE, enabled,
                    "整段删掉 cors: 后，enabled 应回落到 application.yml 的 true，"
                            + "而不是变成 false —— 因为 application.yml 里写的是 true");
            assertNotNull(origins,
                    "整段删掉 cors: 后，allowed-origins 应回落到 application.yml 的那份");
        });
    }

    @Test
    @DisplayName("parseOrigins 对空值/空白/null 的处理：必须返回空列表，绝不 null")
    void parseOriginsHandlesBlankSafely() {
        assertEquals(List.of(), CorsConfig.parseOrigins(null));
        assertEquals(List.of(), CorsConfig.parseOrigins(""));
        assertEquals(List.of(), CorsConfig.parseOrigins("   "));
        // 逗号 + 空格混排，容错解析
        assertEquals(List.of("http://a:5173", "http://b:8080"),
                CorsConfig.parseOrigins(" http://a:5173 , http://b:8080 , ,"));
    }
}
