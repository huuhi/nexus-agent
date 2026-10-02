package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link EncryptorFactory} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 的改动：主密钥支持 Spring 配置项（{@code nexus.agent.api-key-secret}），
 * 环境变量 {@code API_KEY_SECRET} 只作为兜底 —— 之前只认环境变量，
 * 于是「写在配置文件里却报 secret 为空」。
 * <p>
 * ⚠️ 测试 JVM 里由 surefire 注入了 {@code API_KEY_SECRET}（见 nexus-agent-web/pom.xml），
 * 所以「回退环境变量」这条路径也是有值的。
 */
@DisplayName("EncryptorFactory —— 主密钥支持 Spring 配置注入")
class EncryptorFactoryTest {

    private static final String SALT = "0123456789abcdef";

    @AfterEach
    void reset() {
        EncryptorFactory.setConfiguredSecret(null);
    }

    @Test
    @DisplayName("注入的配置值生效：同一加密器能加解密往返")
    void configuredSecretIsUsed() {
        EncryptorFactory.setConfiguredSecret("config-secret-value");
        TextEncryptor encryptor = EncryptorFactory.text(SALT);
        String cipher = encryptor.encrypt("sk-plain-key");
        assertNotEquals("sk-plain-key", cipher);
        assertEquals("sk-plain-key", encryptor.decrypt(cipher));
    }

    @Test
    @DisplayName("换掉主密钥后旧密文解不开 —— 证明注入的值真的被用上了")
    void differentSecretCannotDecrypt() {
        EncryptorFactory.setConfiguredSecret("secret-a");
        String cipher = EncryptorFactory.text(SALT).encrypt("sk-plain-key");

        EncryptorFactory.setConfiguredSecret("secret-b");
        assertThrows(Exception.class, () -> EncryptorFactory.text(SALT).decrypt(cipher));
    }

    @Test
    @DisplayName("清空注入值后回退环境变量：仍能正常工作")
    void fallsBackToEnvVar() {
        EncryptorFactory.setConfiguredSecret(null);
        TextEncryptor encryptor = EncryptorFactory.text(SALT);
        assertEquals("sk-plain-key", encryptor.decrypt(encryptor.encrypt("sk-plain-key")));
    }

    @Test
    @DisplayName("salt 为空：给出可读报错，而不是 NPE")
    void blankSaltIsRejected() {
        assertThrows(IllegalStateException.class, () -> EncryptorFactory.text("   "));
    }
}
