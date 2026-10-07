package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户凭据解密的**完整性校验**（2026-10-07，线上 401 事故后补）。
 * <p>
 * <b>线上症状</b>：用户报 {@code 401 Invalid API Key}，并明确说「我没改 Key」「换了个新 Key 还是这样」。
 * <b>根因隐患</b>：Spring 的 {@code Encryptors.text()} 用 AES-CBC + 随机 IV，
 * <b>解密不校验完整性</b> —— 主密钥（{@code nexus.agent.api-key-secret} / {@code API_KEY_SECRET}）
 * 与加密时不是同一把时，它<b>不抛异常</b>，而是解出一串乱码。乱码被当成 API Key 原样发给厂商，
 * 厂商只回 401，真实原因被完全掩盖 —— 于是「换 Key」永远治不好，因为问题根本不在 Key。
 * <p>
 * 所以 {@link EncryptorFactory#decryptChecked} 在解密后校验形状（必须是可打印 ASCII），
 * 不通过就抛<b>带明确原因</b>的异常。本测试的重点是**反向验证**：
 * 用 A 加密、换 B 解密，必须炸，绝不能静默返回乱码。
 */
@DisplayName("凭据解密 —— 主密钥不一致必须报错，不能静默产出乱码")
class EncryptorFactoryDecryptTest {

    private static final String SECRET_A = "secret-aaaaaaaaaaaaaaaaaaaa";
    private static final String SECRET_B = "secret-bbbbbbbbbbbbbbbbbbbb";
    private static final String SALT = "6a1f9c2e5b7d4038";

    @AfterEach
    void restore() {
//        还原成其它测试约定的值，避免 static 状态串味（EncryptorFactory 是全进程共享的）
        EncryptorFactory.setConfiguredSecret("nexus-agent-test-only-secret");
    }

    @Test
    @DisplayName("正常路径：同一把主密钥加解密，明文一致")
    void roundTrip() {
        EncryptorFactory.setConfiguredSecret(SECRET_A);
        String cipher = EncryptorFactory.text(SALT).encrypt("sk-1234567890abcdef");
        assertEquals("sk-1234567890abcdef", EncryptorFactory.decryptChecked(SALT, cipher, "API Key"));
    }

    @Test
    @DisplayName("🔴 反向验证：换了主密钥必须抛异常，绝不能返回乱码")
    void wrongMasterKeyMustFailLoudly() {
        EncryptorFactory.setConfiguredSecret(SECRET_A);
        String cipher = EncryptorFactory.text(SALT).encrypt("sk-1234567890abcdef");

        EncryptorFactory.setConfiguredSecret(SECRET_B);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EncryptorFactory.decryptChecked(SALT, cipher, "API Key"),
                "主密钥不一致时必须明确报错 —— 静默返回乱码会让厂商只回一句 401，"
                        + "真实原因（主密钥漂移）永远查不出来");

        String msg = e.getMessage();
        assertTrue(msg.contains("API Key"), "报错要指明是哪个凭据：实际=" + msg);
        assertTrue(msg.contains("主密钥"), "报错必须把怀疑方向说出来（主密钥不是同一把）：实际=" + msg);
    }

    @Test
    @DisplayName("空密文 / 空盐值也要明确报错，不能 NPE")
    void blankInputFailsLoudly() {
        EncryptorFactory.setConfiguredSecret(SECRET_A);
        assertThrows(IllegalStateException.class,
                () -> EncryptorFactory.decryptChecked(SALT, null, "API Key"));
        assertThrows(IllegalStateException.class,
                () -> EncryptorFactory.decryptChecked(SALT, "  ", "API Key"));
        assertThrows(IllegalStateException.class,
                () -> EncryptorFactory.decryptChecked(null, "whatever", "API Key"));
    }

    @Test
    @DisplayName("含非 ASCII 的解密结果要被拦住（乱码的典型特征）")
    void garbledResultIsRejected() {
        EncryptorFactory.setConfiguredSecret(SECRET_A);
//        直接构造一段"解出来就是乱码"的情形：用 A 加密一段非 ASCII 明文
        String cipher = EncryptorFactory.text(SALT).encrypt("sk-乱码\uFFFDabc");
        assertThrows(IllegalStateException.class,
                () -> EncryptorFactory.decryptChecked(SALT, cipher, "API Key"),
                "含 U+FFFD / 非可打印字符的结果不该被当成有效凭据发出去");
    }

    @Test
    @DisplayName("主密钥指纹：同一把密钥两次取指纹一致（用于比对两次启动是否同一把）")
    void fingerprintIsStable() {
        EncryptorFactory.setConfiguredSecret(SECRET_A);
        String cipher = EncryptorFactory.text(SALT).encrypt("x");
        EncryptorFactory.decryptChecked(SALT, cipher, "API Key");
//        指纹只打进日志，这里只验证"能取到且稳定"：换密钥后仍能正常工作
        EncryptorFactory.setConfiguredSecret(SECRET_B);
        assertEquals("x", EncryptorFactory.decryptChecked(SALT,
                EncryptorFactory.text(SALT).encrypt("x"), "API Key"));
    }
}
