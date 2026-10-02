package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.RuntimeSecretInitializer;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link RuntimeSecretInitializer} 的验证 —— 用**真实的 Spring 容器**跑，不 mock @Value。
 * <p>
 * 背景（2026-10-03 线上事故）：{@link JwtUtil} 与 {@link EncryptorFactory} 原先直接
 * {@code System.getenv(...)} 读密钥，绕开 Spring —— 用户把密钥写进配置文件后，
 * 应用照样报「没设置」。这个类的职责就是把 Spring 配置值注入给那两个 static 工具类。
 * <p>
 * 这里用 {@link ApplicationContextRunner} 起一个只含该 bean 的迷你容器：
 * 不需要数据库、不需要网络，但 {@code @Value} 注入与 {@code @PostConstruct} 都是真的。
 */
@DisplayName("RuntimeSecretInitializer —— 配置里的密钥真的注入到 static 工具类")
class RuntimeSecretInitializerTest {

    private static final String SALT = "0123456789abcdef";

    /** 故意用带连字符的非 Base64 字符串 —— 这就是昨天把线上打成全站 500 的那种值 */
    private static final String WEIRD_JWT_SECRET = "3f2a-9c81-77de-4b6e-8a5c-1d2e3f4a5b6c";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(RuntimeSecretInitializer.class);

    @AfterEach
    void reset() {
//        static 状态复位，避免污染其它测试
        JwtUtil.setConfiguredSecret(null);
        EncryptorFactory.setConfiguredSecret(null);
    }

    @Test
    @DisplayName("配置项 nexus.agent.jwt-secret 生效：非 Base64 值也能正常签发与解析")
    void jwtSecretFromConfigFile() {
        runner.withPropertyValues("nexus.agent.jwt-secret=" + WEIRD_JWT_SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RuntimeSecretInitializer.class);
                    String token = JwtUtil.createJWT(Map.of("user_id", 42L));
                    assertEquals(42L, JwtUtil.getIdFromToken(token, "user_id"));
                });
    }

    @Test
    @DisplayName("配置项 nexus.agent.api-key-secret 生效：加解密往返正常")
    void apiKeySecretFromConfigFile() {
        runner.withPropertyValues("nexus.agent.api-key-secret=secret-from-config-file")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TextEncryptor encryptor = EncryptorFactory.text(SALT);
                    String cipher = encryptor.encrypt("sk-plain");
                    assertNotEquals("sk-plain", cipher);
                    assertEquals("sk-plain", encryptor.decrypt(cipher));
                });
    }

    @Test
    @DisplayName("只设了环境变量 JWT_SECRET / API_KEY_SECRET：仍能兜底生效")
    void fallbackToEnvVar() {
        runner.withSystemProperties("JWT_SECRET", WEIRD_JWT_SECRET)
                .withSystemProperties("API_KEY_SECRET", "secret-from-env")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String token = JwtUtil.createJWT(Map.of("user_id", 8L));
                    assertEquals(8L, JwtUtil.getIdFromToken(token, "user_id"));
                    assertEquals("sk-plain", EncryptorFactory.text(SALT).decrypt(
                            EncryptorFactory.text(SALT).encrypt("sk-plain")));
                });
    }

    @Test
    @DisplayName("配置项优先于环境变量：两个都给时用配置项的值")
    void configWinsOverEnvVar() {
//        用两个不同的密钥分别签/解，能解开说明用的是配置项那个
        runner.withSystemProperties("API_KEY_SECRET", "env-value")
                .withPropertyValues("nexus.agent.api-key-secret=config-value")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String cipher = EncryptorFactory.text(SALT).encrypt("sk-plain");
//                    换成环境变量的密钥去解 —— 应当解不开，证明生效的是配置项的那个
                    EncryptorFactory.setConfiguredSecret("env-value");
                    try {
                        EncryptorFactory.text(SALT).decrypt(cipher);
                        throw new AssertionError("应当是配置项的值生效，环境变量的值不该解得开");
                    } catch (Exception expected) {
//                        预期：解不开
                    }
                });
    }

    @Test
    @DisplayName("两个都没配：容器照常启动（只 WARN），不能在启动期把类搞成不可用")
    void missingSecretsDoNotBreakStartup() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RuntimeSecretInitializer.class);
//            仍能签发（退化为随机密钥）
            String token = JwtUtil.createJWT(Map.of("user_id", 5L));
            assertEquals(5L, JwtUtil.getIdFromToken(token, "user_id"));
        });
    }
}
