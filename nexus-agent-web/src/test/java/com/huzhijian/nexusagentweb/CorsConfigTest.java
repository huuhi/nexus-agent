package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.CorsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ------------------------------------------------------------------
    // 下面两个用例回答一个部署期的关键问题：
    // 「改 cors 配置要不要重新打 jar？」
    // 答案是**不用** —— 只要环境变量能覆盖到 @Value 读的那个属性键，
    // 运维在服务器上改一行环境变量重启即可，不必回到构建环节。
    //
    // CorsConfig 用的是 @Value("${nexus.agent.cors.allowed-origins:}")，
    // @Value 本身**不做** relaxed binding，但它查的是 Environment，
    // 而 Environment 里的 systemEnvironment 属性源是 SystemEnvironmentPropertySource ——
    // 它会把 "nexus.agent.cors.allowed-origins" 反向映射成
    // "NEXUS_AGENT_CORS_ALLOWED_ORIGINS" 去查。这两个用例锁住这个行为，
    // 万一将来 Spring 改了策略，测试会先炸而不是等上线才发现跨域失效。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("环境变量 NEXUS_AGENT_CORS_ALLOWED_ORIGINS 能覆盖 allowed-origins")
    void envVarOverridesAllowedOrigins() {
        SystemEnvironmentPropertySource ps = new SystemEnvironmentPropertySource(
                "systemEnvironment",
                Map.of("NEXUS_AGENT_CORS_ALLOWED_ORIGINS", "http://120.235.30.202:5173",
                        "NEXUS_AGENT_CORS_ENABLED", "true"));

        assertEquals("http://120.235.30.202:5173",
                ps.getProperty("nexus.agent.cors.allowed-origins"));
        assertEquals("true", ps.getProperty("nexus.agent.cors.enabled"));

        // 串起来：环境变量 → 属性 → parseOrigins 的整条链路
        assertEquals(List.of("http://120.235.30.202:5173"),
                CorsConfig.parseOrigins((String) ps.getProperty("nexus.agent.cors.allowed-origins")));
    }

    @Test
    @DisplayName("反过来：没设的环境变量查不到，会落到 @Value 的默认值")
    void envVarMissingFallsBack() {
        SystemEnvironmentPropertySource ps = new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("SOMETHING_ELSE", "x"));
        assertNull(ps.getProperty("nexus.agent.cors.allowed-origins"));
    }
}
