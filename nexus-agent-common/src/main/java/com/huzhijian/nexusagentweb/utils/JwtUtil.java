package com.huzhijian.nexusagentweb.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static io.jsonwebtoken.SignatureAlgorithm.HS256;

/**
 * JWT工具类 - 优化版
 * <p>
 * 支持从环境变量加载固定密钥，提供安全的解析与验证方法。
 */
public class JwtUtil {

    // 默认过期时间：7天
    private static final long DEFAULT_TTL_MILLIS = 60 * 60 * 1000L * 24 * 7;

    // 优先从环境变量加载密钥，否则生成随机密钥（仅适合单机开发）
    private static final SecretKey SECRET_KEY;
    private static final JwtParser PARSER;

    static {
        String base64Secret = System.getenv("JWT_SECRET");
        if (base64Secret != null && !base64Secret.trim().isEmpty()) {
            byte[] keyBytes = Base64.getDecoder().decode(base64Secret.trim());
            SECRET_KEY = Keys.hmacShaKeyFor(keyBytes);
        } else {
            SECRET_KEY = Keys.secretKeyFor(HS256);
        }
        PARSER = Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build();
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
                .signWith(SECRET_KEY)
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
            Claims claims = PARSER.parseSignedClaims(token).getPayload();
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