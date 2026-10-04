package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.service.WebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册。
 * <p>
 * <b>⚠️ 2026-10-04 重要修复：从 {@code ServerEndpointExporter} 改为 {@code WebSocketConfigurer}。</b>
 * <p>
 * 原来用 {@code ServerEndpointExporter} + {@code @ServerEndpoint} 注册端点。这条路径是
 * <b>JSR-356 原生端点</b>：由 Servlet 容器直接创建与处理，
 * <ul>
 *   <li><b>不经过 Spring MVC 的 DispatcherServlet</b> → {@code LoginCheckInterceptor} 对它
 *       <b>100% 无效</b></li>
 *   <li>{@code ServerEndpointExporter} <b>没有</b> {@code setHandshakeInterceptors} 方法
 *       （已用 javap 核对 6.2.17 的实际 API），也就是说走这条路径<b>根本无法注入握手鉴权</b>
 *       —— 这正是之前"零鉴权"能长期存在的根因</li>
 * </ul>
 * 现在改为 {@link EnableWebSocket} + {@link WebSocketConfigurer}，端点由 Spring 托管，
 * 从而可以通过 {@code addInterceptors(...)} 挂上
 * {@link WebSocketAuthInterceptor}，在<b>握手阶段</b>校验 token。
 * <p>
 * <b>同时补上来源限制</b>：WebSocket <b>不受浏览器同源策略约束</b>（CORS 管不到它），
 * 不限制来源的话任意网站发起的连接都能建立。跨域来源沿用 CORS 的配置
 * （{@code nexus.agent.cors.allowed-origins}），单域部署时留空即禁止跨域。
 */
@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebSocketConfiguration implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfiguration.class);

    private final WebSocketService webSocketService;

    /**
     * 鉴权拦截器带 {@code @ConditionalOnProperty(nexus.agent.security.enabled=true)}，
     * 本地调试关掉鉴权时它**不存在**，用 ObjectProvider 才不会启动失败。
     */
    private final ObjectProvider<WebSocketAuthInterceptor> authInterceptorProvider;

    /** 跨域来源；为空表示不放开（同源部署）。格式与 CORS 的 allowed-origins 一致。 */
    private final String[] allowedOrigins;

    public WebSocketConfiguration(WebSocketService webSocketService,
                                  ObjectProvider<WebSocketAuthInterceptor> authInterceptorProvider,
                                  @Value("${nexus.agent.cors.allowed-origins:}") String allowedOrigins) {
        this.webSocketService = webSocketService;
        this.authInterceptorProvider = authInterceptorProvider;
        this.allowedOrigins = CorsConfig.parseOrigins(allowedOrigins).toArray(new String[0]);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry
                .addHandler(webSocketService, "/api/ws/{userId}")
                // ⚠️ 必须限制来源：WebSocket 不受同源策略约束，
                // 不限制等于任意网站都能连上（能连上只是第一步，token 还会再校验一层，
                // 但没必要让攻击者建得上连接）。
                .setAllowedOrigins(allowedOrigins);

        WebSocketAuthInterceptor auth = authInterceptorProvider.getIfAvailable();
        if (auth != null) {
            registration.addInterceptors(auth);
        } else {
            // 与 LoginCheckInterceptor 的开关保持一致：关鉴权时不拦 WS，
            // 但必须打 WARN —— 否则"本地没开鉴权"会被误当成"线上也没开"
            log.warn("⚠️ WebSocket 鉴权未启用（nexus.agent.security.enabled=false）："
                    + "连接不校验 token，userId 也没有经过验证");
        }

        if (allowedOrigins.length == 0) {
            log.info("WebSocket 已注册（路径 /api/ws/{{userId}}），未配置跨域来源 → 只接受同源连接");
        } else {
            log.info("WebSocket 已注册（路径 /api/ws/{{userId}}），跨域来源={}", (Object) allowedOrigins);
        }
    }
}
