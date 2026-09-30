package com.huzhijian.nexusagentweb.config;


import com.huzhijian.nexusagentweb.interceptor.LoginCheckInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2025/1/17
 * 说明:配置拦截器
 * <p>
 * 鉴权开关由配置控制：{@code nexus.agent.security.enabled}（默认 true，安全优先）。
 * 本地调试若不想带 token，请在自己不提交的 application-dev.yml 里显式设为 false，
 * **不要注释掉 {@code @Configuration}** —— 那样没有任何痕迹，容易误提交，
 * 也看不出当前究竟是不是有鉴权。关闭时启动会打 WARN。
 */
@Configuration
@ConditionalOnProperty(
        name = "nexus.agent.security.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WebInterceptorConfig implements WebMvcConfigurer {
    @Autowired
    private LoginCheckInterceptor loginInterceptor;

//    @Autowired
//    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
//        //注册限流拦截器
//        registry.addInterceptor(rateLimitInterceptor)
//                .addPathPatterns("/**")
//                .order(1);

        //注册登录拦截器
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register","/api/user/password","/api/common/email",
                        // P3-4：API 文档（SpringDoc）。Swagger UI 自己不会带 token，
                        // 不豁免就根本打不开（页面能出、接口拿不到 → 一片 401）。
                        // ⚠️ 代价：文档路径是免鉴权的，所以**是否暴露由
                        //   springdoc.api-docs.enabled / springdoc.swagger-ui.enabled 决定**
                        //   （prod 默认 false，见 application-prod.yml）。
                        //   打开文档前先确认这是你能接受的信息暴露范围。
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"
                )
                .order(2);
    }
}
