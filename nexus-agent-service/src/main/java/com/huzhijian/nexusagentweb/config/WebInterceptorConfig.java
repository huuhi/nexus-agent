package com.huzhijian.nexusagentweb.config;


import com.huzhijian.nexusagentweb.interceptor.LoginCheckInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2025/1/17
 * 说明:配置拦截器
 */
@Configuration//表示这是一个配置类
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
                        "/user/login",
                        "/user/register",
                        "/common/**",
                        "/test",
                        "/major/**",
                        "/job/**",
                        "/review/getReviewByMajorsId/{majorId}",
                        "/review/getReviewByJobId/{jobId}",
                        "/contact/**"
                )
                .order(2);
    }
}
