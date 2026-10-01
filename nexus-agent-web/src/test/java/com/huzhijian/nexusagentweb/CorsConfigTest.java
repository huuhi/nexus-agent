package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.CorsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CorsConfig} 的纯单测。
 * <p>
 * 只测**配置解析**这一段：{@code CorsFilter} 本身依赖 Servlet 体系，构造出来也没法断言
 * "允许了哪些来源"；而"把一行配置解析成域名列表"恰恰是最容易写错的地方
 * （漏 trim、留空项、重复项、写了 '*' 没被发现）。
 */
class CorsConfigTest {

    @Test
    @DisplayName("null / 空串 / 纯空白 → 空列表（视为未配置，不启用跨域）")
    void blankMeansEmpty() {
        assertTrue(CorsConfig.parseOrigins(null).isEmpty());
        assertTrue(CorsConfig.parseOrigins("").isEmpty());
        assertTrue(CorsConfig.parseOrigins("   ").isEmpty());
    }

    @Test
    @DisplayName("逗号分隔、去掉首尾空白")
    void splitAndTrim() {
        assertEquals(List.of("http://localhost:5173", "https://app.example.com"),
                CorsConfig.parseOrigins("http://localhost:5173, https://app.example.com"));
    }

    @Test
    @DisplayName("丢掉空项（多写了逗号不会解析出空来源）")
    void dropEmptyEntries() {
        assertEquals(List.of("http://a.com", "http://b.com"),
                CorsConfig.parseOrigins("http://a.com,,http://b.com,  ,"));
    }

    @Test
    @DisplayName("去重：同一个来源写两遍只保留一个")
    void distinct() {
        assertEquals(List.of("http://a.com"),
                CorsConfig.parseOrigins("http://a.com,http://a.com"));
    }

    @Test
    @DisplayName("通配来源 '*' 原样保留（由 CorsFilter 打 WARN 提醒）")
    void wildcardKept() {
        assertEquals(List.of("*"), CorsConfig.parseOrigins(" * "));
    }
}
