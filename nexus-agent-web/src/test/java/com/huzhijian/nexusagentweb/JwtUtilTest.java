package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtUtil} 的纯单测，重点覆盖 2026-10-03 那个把线上打成全站 500 的缺陷。
 * <p>
 * 原实现在 {@code static {}} 块里 {@code Base64.getDecoder().decode(System.getenv("JWT_SECRET"))}：
 * 用户填了带 {@code -} 的普通字符串 → {@code IllegalArgumentException: Illegal base64 character 2d}
 * → <b>类初始化永久失败</b> → 之后每次访问都是 {@code NoClassDefFoundError: Could not initialize class JwtUtil}
 * → 登录、鉴权、所有接口一起 500，且报错完全指不到 JWT_SECRET 身上。
 * <p>
 * 这类 bug 的特点：**纯单测覆盖不到，因为测试环境的环境变量是"对的"**。
 * 所以这组用例直接针对"用户填了各种奇怪的值"。
 */
@DisplayName("JwtUtil —— 签名密钥容错（含非 Base64 / 短密钥）")
class JwtUtilTest {

    @AfterEach
    void reset() {
//        static 状态复位，避免污染其它用到 JwtUtil 的测试
        JwtUtil.setConfiguredSecret(null);
    }

    @Test
    @DisplayName("带连字符的非 Base64 密钥：不能抛异常（线上全站 500 就是这个）")
    void nonBase64SecretWithDash() {
        JwtUtil.setConfiguredSecret("3f2a-9c81-77de-4b6e-8a5c-1d2e3f4a5b6c");
        String token = assertDoesNotThrow(() -> JwtUtil.createJWT(Map.of("user_id", 42L)));
        assertEquals(42L, JwtUtil.getIdFromToken(token, "user_id"));
        assertTrue(JwtUtil.isTokenValid(token));
    }

    @Test
    @DisplayName("短于 32 字节的密钥：自动派生，不能抛异常")
    void shortSecretIsDerived() {
        JwtUtil.setConfiguredSecret("abc");
        String token = assertDoesNotThrow(() -> JwtUtil.createJWT(Map.of("user_id", 7L)));
        assertEquals(7L, JwtUtil.getIdFromToken(token, "user_id"));
    }

    @Test
    @DisplayName("标准 Base64 密钥：保持兼容（历史部署方式不变）")
    void base64SecretStillWorks() {
        String b64 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
        JwtUtil.setConfiguredSecret(b64);
        String token = JwtUtil.createJWT(Map.of("user_id", 1L));
        assertEquals(1L, JwtUtil.getIdFromToken(token, "user_id"));
    }

    @Test
    @DisplayName("含空格的密钥：先 trim 再用，不能抛异常")
    void secretWithSurroundingSpaces() {
        JwtUtil.setConfiguredSecret("  my-plain-jwt-secret-with-dashes  ");
        String token = assertDoesNotThrow(() -> JwtUtil.createJWT(Map.of("user_id", 9L)));
        assertEquals(9L, JwtUtil.getIdFromToken(token, "user_id"));
    }

    @Test
    @DisplayName("换掉密钥后：旧 token 必须解析失败（证明注入的值真的生效了）")
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtUtil.setConfiguredSecret("key-number-one-aaaaaaaaaaaaaaaaaaaa");
        String token = JwtUtil.createJWT(Map.of("user_id", 1L));
        assertEquals(1L, JwtUtil.getIdFromToken(token, "user_id"));

        JwtUtil.setConfiguredSecret("key-number-two-bbbbbbbbbbbbbbbbbbbb");
        assertTrue(JwtUtil.parseJWT(token).isEmpty());
        assertFalse(JwtUtil.isTokenValid(token));
    }

    @Test
    @DisplayName("没配密钥：退化为随机密钥，仍能签发与解析（不阻塞启动）")
    void randomKeyFallback() {
        JwtUtil.setConfiguredSecret(null);
        String token = assertDoesNotThrow(() -> JwtUtil.createJWT(Map.of("user_id", 5L)));
        assertEquals(5L, JwtUtil.getIdFromToken(token, "user_id"));
    }
}
