package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.StartupConfigValidator;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StartupConfigValidator 的纯单元测试。
 * <p>
 * 用 {@link MockEnvironment} 控制配置来源 —— 它不包含 systemEnvironment 属性源，
 * 因此测试结果不受运行机器上真实环境变量的影响，是稳定的。
 * <p>
 * 注意：校验项里的环境变量名（如 {@code API_KEY_SECRET}）在真实环境同样能通过
 * {@code environment.getProperty} 读到（Spring 自带 systemEnvironment 属性源），
 * 所以这里统一用 MockEnvironment 设置即可。
 */
class StartupConfigValidatorTest {

    /** 把全部校验项都配上 */
    private MockEnvironment fullEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url",
                "jdbc:postgresql://10.0.0.1:5432/nexus_agent?stringtype=unspecified");
        env.setProperty("langchain4j.open-ai.streaming-chat-model.api-key", "sk-deepseek");
        env.setProperty("API_KEY_SECRET", "s3cret");
        env.setProperty("JWT_SECRET", "anVzdC1hLXRlc3Qtc2VjcmV0");
        env.setProperty("langchain4j.open-ai.embedding-model.api-key", "sk-embed");
        env.setProperty("langchain4j.open-ai.chat-model.api-key", "sk-moonshot");
        env.setProperty("spring.data.redis.host", "10.0.0.1");
        env.setProperty("spring.mail.username", "someone@qq.com");
        env.setProperty("spring.mail.password", "smtp-pass");
        return env;
    }

    private StartupConfigValidator validator(MockEnvironment env, boolean failFast) {
        AgentProperties props = new AgentProperties();
        props.getStartup().setFailFast(failFast);
        return new StartupConfigValidator(env, props);
    }

    @Test
    @DisplayName("全部配置齐全时通过，不抛异常")
    void passesWhenAllPresent() {
        assertDoesNotThrow(() -> validator(fullEnv(), true).validate());
    }

    @Test
    @DisplayName("缺必需配置 + fail-fast=true → 抛异常阻止启动")
    void throwsOnMissingRequiredWhenFailFast() {
        MockEnvironment env = fullEnv();
        env.setProperty("spring.datasource.url", "");   // 必需项置空

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator(env, true).validate());

        assertTrue(e.getMessage().contains("配置自检未通过"), "异常信息应说明自检未通过：" + e.getMessage());
    }

    @Test
    @DisplayName("缺必需配置 + fail-fast=false → 不抛异常（逃生舱可用）")
    void doesNotThrowWhenFailFastDisabled() {
        MockEnvironment env = fullEnv();
        env.setProperty("spring.datasource.url", "");

        assertDoesNotThrow(() -> validator(env, false).validate());
    }

    @Test
    @DisplayName("只缺建议级配置（JWT_SECRET 等）不影响启动")
    void recommendedMissingIsNotFatal() {
        MockEnvironment env = fullEnv();
        env.setProperty("JWT_SECRET", "");
        env.setProperty("langchain4j.open-ai.embedding-model.api-key", "");
        env.setProperty("spring.mail.username", "");

        assertDoesNotThrow(() -> validator(env, true).validate());
    }

    @Test
    @DisplayName("未解析的占位符（如 ${DEEPSEEK}）按缺失处理")
    void unresolvedPlaceholderTreatedAsMissing() {
        MockEnvironment env = fullEnv();
        // prod 模板的写法；变量没设时 Spring 会给出带 ${} 的原值或直接抛异常
        env.setProperty("langchain4j.open-ai.streaming-chat-model.api-key", "${DEEPSEEK}");

        assertThrows(IllegalStateException.class, () -> validator(env, true).validate());
    }

    @Test
    @DisplayName("占位符解析直接抛异常时也按缺失处理，不把异常抛给启动流程")
    void placeholderResolutionExceptionTreatedAsMissing() {
        MockEnvironment throwing = new MockEnvironment() {
            @Override
            public String getProperty(String key) {
                throw new IllegalArgumentException("Could not resolve placeholder '" + key + "'");
            }
        };
        AgentProperties props = new AgentProperties();
        props.getStartup().setFailFast(true);

        // 期望是我们自己的 IllegalStateException（自检汇总），而不是占位符异常本身
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new StartupConfigValidator(throwing, props).validate());
        assertTrue(e.getMessage().contains("配置自检未通过"));
    }

    @Test
    @DisplayName("配置项名为空白字符也算缺失")
    void blankValueCountsAsMissing() {
        MockEnvironment env = fullEnv();
        env.setProperty("API_KEY_SECRET", "   ");

        assertThrows(IllegalStateException.class, () -> validator(env, true).validate());
    }

    @Test
    @DisplayName("设计假设：真实 Environment 能用属性键读到同名环境变量")
    void standardEnvironmentExposesEnvVarsAsProperties() {
        // StartupConfigValidator 把 JWT_SECRET / API_KEY_SECRET 当普通属性键读取，
        // 前提是 Spring 的 systemEnvironment 属性源把环境变量暴露为属性。
        // 这条假设一旦不成立，真实环境会把已配置的密钥误判为缺失 —— 必须守住。
        StandardEnvironment env = new StandardEnvironment();
        String probeKey = System.getenv().keySet().stream()
                .filter(k -> System.getenv(k) != null && !System.getenv(k).isBlank())
                .findFirst()
                .orElse(null);
        assertNotNull(probeKey, "测试机至少要有一个非空环境变量");
        assertEquals(System.getenv(probeKey), env.getProperty(probeKey));
    }
}
