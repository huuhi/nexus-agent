package com.huzhijian.nexusagentweb.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static io.jsonwebtoken.SignatureAlgorithm.HS256;

/**
 * JWT工具类。
 * <p>
 * <b>2026-10-03 改动</b>：签名密钥原先只从 {@code System.getenv("JWT_SECRET")} 读 ——
 * 这条路**绕开 Spring**，写在 yml / {@code .env.properties} / 面板配置里的一律读不到，
 * 表现为「明明配了，重启后所有 token 还是失效」。
 * 现在改为：**Spring 配置项 {@code nexus.agent.jwt-secret} 优先，环境变量 {@code JWT_SECRET} 兜底**，
 * 由 {@code RuntimeSecretInitializer} 在启动时把配置值注入进来。
 * <p>
 * 密钥解析是**惰性**的（第一次签发或解析 token 时才做），好处：
 * <ol>
 *   <li>不必强依赖 Spring 容器的初始化顺序；</li>
 *   <li>纯单测里没注入也能用（自动回退环境变量，再没有才随机生成）；</li>
 *   <li>注入器把新值塞进来后，下一次访问会重新解析。</li>
 * </ol>
 */
public class JwtUtil {

    // 默认过期时间：7天
    private static final long DEFAULT_TTL_MILLIS = 60 * 60 * 1000L * 24 * 7;

    /** HS256 要求密钥至少 256 bit = 32 字节 */
    private static final int HS256_KEY_BYTES = 32;

    /**
     * Spring 注入的配置值（{@code nexus.agent.jwt-secret}）。未注入时为 null。
     */
    private static volatile String configuredSecret;

    /**
     * 密钥 + 解析器打包成一个不可变对象，一次性替换，避免出现「key 换了但 parser 还是旧的」。
     */
    private static volatile KeyHolder holder;

    private record KeyHolder(SecretKey key, JwtParser parser) {
    }

    /**
     * 由 {@code RuntimeSecretInitializer} 在容器启动时调用。
     * <p>
     * 传 null 或空白表示「没配」，此时回退环境变量 {@code JWT_SECRET}；
     * 两者都没有才随机生成（仅适合单机开发 —— 重启即失效）。
     */
    public static void setConfiguredSecret(String secret) {
        configuredSecret = (secret == null || secret.isBlank()) ? null : secret.trim();
//        置空后下一次访问会重新解析，保证注入的值立刻生效
        holder = null;
    }

    private static KeyHolder holder() {
        KeyHolder h = holder;
        if (h == null) {
            synchronized (JwtUtil.class) {
                h = holder;
                if (h == null) {
                    SecretKey key = resolveKey();
                    h = new KeyHolder(key, Jwts.parser().verifyWith(key).build());
                    holder = h;
                }
            }
        }
        return h;
    }

    private static SecretKey resolveKey() {
        String raw = configuredSecret;
        if (raw == null) {
            raw = System.getenv("JWT_SECRET");
        }
        if (raw == null || raw.isBlank()) {
//        与改版前保持一致：没配就随机生成。危险之处在于重启后所有 token 立即失效，
//        由 StartupConfigValidator 与 RuntimeSecretInitializer 各自打 WARN。
            return Keys.secretKeyFor(HS256);
        }
        return Keys.hmacShaKeyFor(keyBytes(raw.trim()));
    }

    /**
     * 把用户填的密钥字符串转成 HS256 要求的密钥字节（≥32 字节）。
     * <p>
     * <b>为什么不能只认 Base64</b>：线上出过一次全站 500 —— 用户填的是带 {@code -} 的普通字符串
     * （如 {@code 3f2a-...}），{@code Base64.getDecoder().decode()} 抛
     * {@code IllegalArgumentException: Illegal base64 character 2d}；
     * 而这段代码原本在 {@code static {}} 块里，异常会让 <b>类初始化永久失败</b>，
     * 之后每一次访问都变成 {@code NoClassDefFoundError: Could not initialize class JwtUtil}
     * —— 登录、鉴权、所有接口一起挂掉，且错误信息完全指不到真正的原因。
     * <p>
     * 所以现在**两种写法都认**，并且永不因格式问题抛异常：
     * <ol>
     *   <li>能按 Base64 解开 → 用解出来的字节（历史行为，兼容已有部署）；</li>
     *   <li>解不开（含 {@code -}、空格等）→ 当作普通字符串的 UTF-8 字节；</li>
     *   <li>无论哪种，不足 32 字节就用 SHA-256 派生到 32 字节（HS256 的硬性要求）。</li>
     * </ol>
     * ⚠️ 派生会改变密钥：如果以后把 JWT_SECRET 从「短串」改成「长串」，
     * 已签发的 token 会失效（短串走派生、长串走原始字节）。这是可接受的取舍 ——
     * 总比「填错格式就全站 500」好。
     */
    private static byte[] keyBytes(String raw) {
        byte[] bytes = null;
        try {
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException notBase64) {
            bytes = raw.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes == null || bytes.length == 0) {
            bytes = raw.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length >= HS256_KEY_BYTES) {
            return bytes;
        }
//        短密钥：用 SHA-256 派生到 32 字节，保证 HMAC-SHA256 的密钥长度要求
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
//            SHA-256 是 JDK 强制实现的算法，理论上到不了这里
            throw new IllegalStateException("JDK 不支持 SHA-256，无法派生 JWT 密钥", e);
        }
    }

    /**
     * 创建JWT（指定过期时间）
     */
    public static String createJWT(long ttlMillis, Map<String, Object> claims) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(holder().key())
                .compact();
    }

    /**
     * 创建JWT（使用默认过期时间7天）
     */
    public static String createJWT(Map<String, Object> claims) {
        return createJWT(DEFAULT_TTL_MILLIS, claims);
    }

    /**
     * 安全解析JWT，返回 Optional 避免异常扩散
     */
    public static Optional<Claims> parseJWT(String token) {
        try {
            Claims claims = holder().parser().parseSignedClaims(token).getPayload();
            return Optional.of(claims);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 验证JWT是否有效（未过期、签名正确）
     */
    public static boolean isTokenValid(String token) {
        return parseJWT(token).isPresent();
    }

    /**
     * 获取Long类型claim（内部解析一次）
     */
    public static Long getIdFromToken(String token, String key) {
        return parseJWT(token)
                .map(claims -> claims.get(key))
                .map(value -> {
                    if (value instanceof Number) {
                        return ((Number) value).longValue();
                    }
                    return Long.parseLong(value.toString());
                })
                .orElse(null);
    }

    /**
     * 获取String类型claim
     */
    public static String getUsernameFromToken(String token) {
        return parseJWT(token)
                .map(claims -> claims.get("username", String.class))
                .orElse(null);
    }

    /**
     * 通用方法：从Token中提取任意字符串声明
     */
    public static String getStringClaim(String token, String key) {
        return parseJWT(token)
                .map(claims -> claims.get(key, String.class))
                .orElse(null);
    }

    /**
     * 通用方法：从Token中提取任意Long声明
     */
    public static Long getLongClaim(String token, String key) {
        return getIdFromToken(token, key);
    }
}
