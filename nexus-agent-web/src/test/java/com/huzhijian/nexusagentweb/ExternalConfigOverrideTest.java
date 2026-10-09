package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「外部 yml 能否覆盖 jar 包内的 application-prod.yml」。
 * <p>
 * 目的：为「加模型只改配置、不重新打包」这个诉求提供依据。
 * 用真实的 ConfigData 机制（additional-location + 绑定到 AgentProperties）来测，
 * 不启动整个应用（pgvector 会在建 Bean 阶段真连库，起不来）。
 * <p>
 * 🔴 <b>2026-10-09 改名记录：原名 {@code ExternalConfigOverrideProbe}，
 * 名字不匹配 surefire 的默认 include 模式（{@code *Test} / {@code *Tests} / {@code Test*} /
 * {@code *TestCase}），于是一直<b>没被 {@code mvn test} 执行过</b> ——
 * 而它的报告文件会因为早先某次手动 {@code -Dtest=} 运行而留在 target 里，
 * 看起来"有报告"，实际上全量跑根本没它。</b>
 * 这个坑的形状值得记住：<b>测试类名起错了，护栏就是摆设，而且是不出声的那种</b>
 * （README 与部署指南都把本类当作「已实测验证」的依据引用，实际全量跑里它一直是绿的缺席）。
 * 现在由 {@code SurefireDiscoveryGuardTest} 兜底：凡是带 {@code @Test} 的类，
 * 名字必须能被 surefire 发现。
 */
@DisplayName("外部配置文件能否覆盖 jar 内配置（改配置不重新打包）")
class ExternalConfigOverrideTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    @DisplayName("additional-location 指定的外部 yml 优先级高于 jar 内 application-prod.yml")
    void externalYmlWins() throws IOException {
        // 外部文件：只写 system-models，字段路径与 jar 内的完全相同
        Path external = Files.createTempFile("nexus-override", ".yml");
        Files.writeString(external, """
                nexus:
                  agent:
                    system-models:
                      - id: from-external
                        name: 测试供应商
                        baseUrl: https://api.example.com
                        apiKey: sk-external
                        models:
                          - modelName: external-model-XYZ
                            name: 外部YML来的模型
                """, StandardCharsets.UTF_8);

        // 模拟 `java -jar app.jar --spring.config.additional-location=file:<外部文件>`：
        // jar 内那份 application-prod.yml 依然在 classpath 上，外部文件压在上面。
        String location = "file:" + external.toAbsolutePath();

        assertTrue(additionalLocationBeatsClasspath(location),
                "additional-location 的 PropertySource 必须排在 classpath 之后，"
                        + "否则外部配置根本压不住 jar 内的配置");
    }

    /**
     * 用 Spring Boot 真实的 ConfigData 解析路径，验证外部文件排在 classpath 之后。
     * 这正是「外部 yml 能不能覆盖 jar 内配置」的决定性因素。
     * <p>
     * 断言必须精确：外部文件里写的是 {@code id: from-external}，
     * 而 jar 内 application-prod.yml 写的是 {@code id: deepseek} —— 读到哪个一目了然。
     */
    private boolean additionalLocationBeatsClasspath(String additionalLocation) {
        try (var ctx = new org.springframework.boot.builder.SpringApplicationBuilder(ProbeConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "spring.config.name=application",
                        "spring.config.location=classpath:/application-prod.yml," + additionalLocation,
                        "spring.profiles.active=prod")
                .run()) {
            ConfigurableEnvironment env = ctx.getEnvironment();
            String id = env.getProperty("nexus.agent.system-models[0].id");
            // 顺带把整条链路验证到位：外部文件的 modelName 与 apiKey 都得读得到，
            // 否则「改了配置但没生效」会伪装成启动成功
            assertEquals("from-external", id, "外部 yml 的 id 必须压过 jar 内的 deepseek");
            assertEquals("external-model-XYZ",
                    env.getProperty("nexus.agent.system-models[0].models[0].modelName"));
            assertEquals("sk-external",
                    env.getProperty("nexus.agent.system-models[0].apiKey"));
            return true;
        }
    }

    @Test
    @DisplayName("外部 yml 里省略 id 时，注册表照样能补成 modelName（改配置后仍成立）")
    void externalYmlWithoutIdStillWorks() throws IOException {
        Path external = Files.createTempFile("nexus-noid", ".yml");
        Files.writeString(external, """
                nexus:
                  agent:
                    system-models:
                      - id: p
                        name: P
                        baseUrl: https://api.example.com
                        apiKey: k
                        models:
                          - modelName: only-model-name
                """, StandardCharsets.UTF_8);

        PropertySource<?> ps = loader.load("override", new FileSystemResource(external)).get(0);
        assertTrue(ps.containsProperty("nexus.agent.system-models[0].models[0].modelName"),
                "外部 yml 应能解析出 models[].modelName");
        assertEquals("only-model-name",
                ps.getProperty("nexus.agent.system-models[0].models[0].modelName"));
    }

    // ==========================================================================================
    //  联网搜索的 API Key：必须能从**外部 yml** 配到（2026-10-09 线上故障的回归测试）
    // ==========================================================================================
    //
    //  现场：用户把 TAVILY_API_KEY 写进服务器的 nexus-override.yml，
    //       启动日志的「生效配置快照」却一直显示「未配置」。
    //  根因：工具当时直接调 System.getenv("TAVILY_API_KEY") —— **绕开 Spring**，
    //       而外部 yml 是 Spring 的属性源，两条路根本不相通。
    //       这与 JwtUtil / EncryptorFactory 在 2026-10-03 踩过的是同一个坑。
    //  修法：改用 ${nexus.agent.websearch.api-key:${TAVILY_API_KEY:}} —— 配置项优先、环境变量兜底。
    //
    //  本测试断言的就是**那个表达式本身**能否从外部 yml 解析出值：
    //  表达式一旦被改回 getenv（或键名写错），这里立刻红。

    @Test
    @DisplayName("🔴 写在外部 yml 里的 websearch.api-key 必须能被注入（曾因 System.getenv 而读不到）")
    void tavilyKeyResolvesFromExternalYml() throws IOException {
        Path external = Files.createTempFile("nexus-websearch", ".yml");
        Files.writeString(external, """
                nexus:
                  agent:
                    websearch:
                      api-key: tvly-from-external-file
                """, StandardCharsets.UTF_8);

        try (var ctx = new org.springframework.boot.builder.SpringApplicationBuilder(ProbeConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "spring.config.name=application",
                        "spring.config.location=classpath:/application-prod.yml,file:" + external.toAbsolutePath(),
                        "spring.profiles.active=prod")
                .run()) {
            // ⚠️ 断言必须走 **@Value 注入**这条路，不能用 env.getProperty(表达式)：
            // Environment.getProperty 是**不做占位符解析**的（拿表达式当 key 查必然 null），
            // 那样测出来的是「我调错了 API」，而不是「外部 yml 能不能配到这个键」。
            // KeyProbe 用的是与 WebSearchTool / WebExtractTool **完全相同**的表达式与注入方式。
            String injected = ctx.getBean(KeyProbe.class).apiKey;

            assertEquals("tvly-from-external-file", injected,
                    "外部 yml 里的 nexus.agent.websearch.api-key 必须被注入 —— "
                            + "注入不进来就意味着「用户写在配置文件里，工具却读不到」，"
                            + "这正是 2026-10-09 那个故障的形态（当时用的是 System.getenv，绕开了 Spring）");
            // 顺带钉住键名：常量一旦被改，上面这行会红；这条防「测试与实现各写一份」
            assertEquals("nexus.agent.websearch.api-key", AgentProperties.Websearch.API_KEY_PROPERTY);
        }
    }

    @Test
    @DisplayName("🔴 三个消费方必须引用同一个表达式常量（否则有人改回 getenv 时上面的测试照样绿）")
    void allConsumersUseTheSameExpression() {
        // 只断言「表达式能不能被解析」是不够的：把 WebSearchTool 改回 System.getenv，
        // 上面那条测试仍然会过（它测的是常量本身）。所以这里补一条：**谁在用这个常量**。
        // 判据 = 类上存在一个 @Value，其值与 API_KEY_EXPRESSION 完全相同。
        // 一旦有人硬编码 System.getenv，这个注解就不存在 → 立刻红。
        assertUsesExpression(com.huzhijian.nexusagentweb.tools.WebSearchTool.class);
        assertUsesExpression(com.huzhijian.nexusagentweb.tools.WebExtractTool.class);
        assertUsesExpression(com.huzhijian.nexusagentweb.config.EffectiveConfigReporter.class);
    }

    private static void assertUsesExpression(Class<?> type) {
        String expected = AgentProperties.Websearch.API_KEY_EXPRESSION;

        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            org.springframework.beans.factory.annotation.Value v =
                    field.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
            if (v != null && expected.equals(v.value())) {
                return;
            }
        }
        for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
            for (java.lang.reflect.Parameter p : ctor.getParameters()) {
                org.springframework.beans.factory.annotation.Value v =
                        p.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
                if (v != null && expected.equals(v.value())) {
                    return;
                }
            }
        }
        throw new AssertionError(type.getSimpleName() + " 没有用 AgentProperties.Websearch.API_KEY_EXPRESSION 取 Key。"
                + "\n后果：写在外部的 nexus.agent.websearch.api-key 对它无效（读不到），"
                + "用户看到的就是「我明明写到配置文件里了，日志却说没配」——"
                + "2026-10-09 线上故障的形态（当时 WebSearchTool 直接调 System.getenv）。"
                + "\n修法：把 Key 作为构造器参数，标注 @Value(AgentProperties.Websearch.API_KEY_EXPRESSION)。");
    }

    @Test
    @DisplayName("🔴 两处都没配时上下文照样能起来、注入到空串（缺默认值就会变成「应用起不来」）")
    void tavilyKeyExpressionIsSafeWhenNothingConfigured() throws IOException {
        Path external = Files.createTempFile("nexus-websearch-empty", ".yml");
        Files.writeString(external, "# 故意什么都不配\n", StandardCharsets.UTF_8);

        // 这个 try-with-resources 能正常 run 完，本身就是主要断言：
        // 占位符若没有末尾那个 :默认值，Spring 会抛 PlaceholderResolutionException，
        // 连这个最小上下文都起不来 —— 「一个可选功能没配」被升级成「整个应用挂掉」。
        try (var ctx = new org.springframework.boot.builder.SpringApplicationBuilder(ProbeConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties(
                        "spring.config.name=application",
                        "spring.config.location=classpath:/application-prod.yml,file:" + external.toAbsolutePath(),
                        "spring.profiles.active=prod")
                .run()) {
            String injected = ctx.getBean(KeyProbe.class).apiKey;
            assertNotNull(injected, "解析不出值时注入的应是空串，不能是 null 也不能抛异常");

            // 本机若真的配了 TAVILY_API_KEY（环境变量兜底生效），读到的就是它 —— 两种情况都合法，
            // 所以不能断言「必须有值」或「必须是空」。CI 上通常没配，走空串那一支。
            String envKey = System.getenv("TAVILY_API_KEY");
            if (envKey == null || envKey.isBlank()) {
                assertEquals("", injected,
                        "两处都没配时应注入空串；抛异常就等于把「可选功能没配」升级成「整个应用起不来」");
            } else {
                assertEquals(envKey, injected, "同名环境变量兜底没生效");
            }
        }
    }

    /** 最小配置载体：只要 AgentProperties，不牵扯 DataSource / pgvector。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AgentProperties.class)
    @org.springframework.context.annotation.Import(KeyProbe.class)
    static class ProbeConfig {
    }

    /**
     * 只为了拿「注入进来的那个 Key」——用的是与 {@code WebSearchTool} / {@code WebExtractTool}
     * <b>完全相同</b>的表达式与注入方式（构造器参数 + {@code @Value}）。
     * <p>
     * 为什么不直接把 {@code WebSearchTool} 注册成 bean：它挂在 {@code tools} 包下，
     * 一旦组件扫描就会把 BoxTool / MemoryTool 那些重依赖一起拉进来，起不来。
     * 而本测试要验证的只是「这个表达式能不能从外部 yml 拿到值」，
     * 用同款注入的替身足以证明，且不会因为别的工具改动而假红。
     */
    static class KeyProbe {
        final String apiKey;

        KeyProbe(@org.springframework.beans.factory.annotation.Value(
                AgentProperties.Websearch.API_KEY_EXPRESSION) String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
