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
                        "/api/user/register","/api/user/password","/api/common/email"
                )
                .order(2);
    }
}
