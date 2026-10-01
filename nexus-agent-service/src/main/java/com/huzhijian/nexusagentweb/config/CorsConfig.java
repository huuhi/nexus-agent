package com.huzhijian.nexusagentweb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * 跨域（CORS）配置。
 * <p>
 * <b>为什么是 {@code CorsFilter} 而不是 {@code WebMvcConfigurer#addCorsMappings}</b>：
 * 前者是 Servlet 过滤器，在 {@code LoginCheckInterceptor} **之前**执行，
 * 并且会**直接短路** OPTIONS 预检请求（不会继续走过滤器链）。
 * 这一点很关键 —— 预检请求是不带 {@code token} 头的，
 * 若走 MVC 那套就有可能被登录拦截器判成未登录而返回 401，于是浏览器认为预检失败，
 * 真正的 POST/GET 根本发不出去（现象是"配了跨域还是被拦"）。
 * <p>
 * <b>安全边界</b>：来源必须由配置显式给出，且生产默认**关闭**
 * （见 {@code application-prod.yml}）。历史版本这里是
 * {@code setAllowedOriginPatterns(List.of("*"))} + {@code setAllowCredentials(true)}，
 * 等于把接口对**任意网站**开放：任意网页只要拿到一个 token（例如从被 XSS 的页面、
 * 或用户自己的浏览器）就能读写本服务的数据。现在改为「不给域名就不开」。
 *
 * @see WebInterceptorConfig
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "nexus.agent.cors.enabled", havingValue = "true", matchIfMissing = false)
public class CorsConfig {

    /**
     * 本项目鉴权用的请求头名。**必须放行**，否则浏览器预检（Access-Control-Allow-Headers）
     * 不含它，带 {@code token} 的请求会被判定为不允许，请求直接发不出去。
     */
    private static final String HEADER_TOKEN = "token";

    @Value("${nexus.agent.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsFilter corsFilter() {
        List<String> origins = parseOrigins(allowedOrigins);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (origins.isEmpty()) {
            // 开了开关却没给域名：绝不能退化成"放行所有"（那就等于把接口公开了），
            // 只能当没配 —— 不注册任何规则，浏览器依旧拦截，行为与关闭一致。
            log.warn("""
                    nexus.agent.cors.enabled=true，但 nexus.agent.cors.allowed-origins 为空 —— \
                    本次不注册任何跨域规则（等同关闭）。\
                    请给出明确的前端来源，例如 http://localhost:5173""");
            return new CorsFilter(source);
        }
        if (origins.contains(CorsConfiguration.ALL)) {
            log.warn("跨域配置了通配来源 '*'：任何网站都能读取本服务的响应，仅建议本地临时调试使用。");
        }

        CorsConfiguration config = new CorsConfiguration();
        // 用 patterns 而不是 allowedOrigins：既能精确匹配，也支持 http://localhost:* 这类写法。
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // ⚠️ 必须放行 token 头（本项目鉴权靠它），以及 Content-Type: application/json
        // （它不属于"简单请求头"，不放行的话 POST JSON 过不去）
        config.setAllowedHeaders(List.of(HEADER_TOKEN, "Content-Type"));
        // 本项目用 token 头鉴权、不用 Cookie，所以不需要 credentials。
        // 不开还有个好处：可以合法地配合通配来源使用（开了就必须列出精确域名）。
        config.setAllowCredentials(false);
        // 预检结果缓存 1 小时，省掉每个请求前的 OPTIONS
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        log.info("已启用跨域：来源={}，放行请求头={}", origins, config.getAllowedHeaders());
        return new CorsFilter(source);
    }

    /**
     * 把 {@code "http://a:5173, http://b:8080"} 解析成列表：去空白、丢空项、去重。
     * <p>
     * 抽成静态方法是为**单测**——{@code CorsFilter} 本身依赖 Servlet 体系不好直接断言，
     * 而"配置解析"恰恰是最容易写错、也最该被测试的一点。
     */
    public static List<String> parseOrigins(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
