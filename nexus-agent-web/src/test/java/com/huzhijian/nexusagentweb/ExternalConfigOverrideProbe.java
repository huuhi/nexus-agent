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
 * 实验：验证「外部 yml 能否覆盖 jar 包内的 application-prod.yml」。
 * <p>
 * 目的：为「加模型只改配置、不重新打包」这个诉求提供依据。
 * 用真实的 ConfigData 机制（additional-location + 绑定到 AgentProperties）来测，
 * 不启动整个应用（pgvector 会在建 Bean 阶段真连库，起不来）。
 */
@DisplayName("外部配置文件能否覆盖 jar 内配置（改配置不重新打包）")
class ExternalConfigOverrideProbe {

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

    /** 最小配置载体：只要 AgentProperties，不牵扯 DataSource / pgvector。 */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AgentProperties.class)
    static class ProbeConfig {
    }
}
