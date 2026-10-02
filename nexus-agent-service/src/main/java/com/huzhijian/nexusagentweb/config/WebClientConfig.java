package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;


/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/30
 * 说明: 沙盒服务（FastAPI）的 HTTP 客户端。
 * <p>
 * 连接池与**响应超时**都在这里配。响应超时是 P2-4 补的：原先只有连接池参数，
 * 没有 {@code responseTimeout} —— 沙盒挂起/网络黑洞时工具会**无限等待**，
 * 整个 SSE 请求跟着卡死（用户只看到「一直不出字」，毫无线索）。
 * 现在可配（{@code nexus.agent.tools.http-timeout}，默认 100s，刻意小于 SSE 超时，
 * 以便先返回结构化的 TIMEOUT 结果、而不是掐断整条流）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final AgentProperties agentProperties;

    /**
     * 沙盒服务地址。
     * <p>
     * 2026-10-03：原先是 {@code System.getenv().getOrDefault("BASE_URL", ...)}，
     * 绕开 Spring —— 写在配置文件里的 BASE_URL 读不到。
     * 现在按 Spring 的方式解析：配置项 {@code nexus.agent.sandbox.base-url} 优先，
     * 回退环境变量 {@code BASE_URL}，再没有才用默认值。
     * 注意容器里 {@code localhost} 指的是容器自己，连宿主机上的沙盒要写
     * {@code http://host.docker.internal:8000}。
     */
    @Value("${nexus.agent.sandbox.base-url:${BASE_URL:http://localhost:8000}}")
    private String sandboxBaseUrl;

    @Bean
    @Primary
    public WebClient webClient() {
        log.info("沙盒服务地址 BASE_URL = {}", sandboxBaseUrl);
        Duration httpTimeout = agentProperties.getTools().getHttpTimeout();
        ConnectionProvider httpPool = ConnectionProvider.builder("http_pool")
                .disposeTimeout(Duration.ofSeconds(20))
                .maxConnectionPools(100) // 最大连接100
                .pendingAcquireMaxCount(500) // 队列最大长度
                .pendingAcquireTimeout(Duration.ofSeconds(30)) // 获取连接最大等待数
                .maxIdleTime(Duration.ofSeconds(60)) // 空闲连接 60秒后关闭
                .evictInBackground(Duration.ofMinutes(1))  // 清理后台连接间隔
                .build();
        return WebClient.builder()
                .baseUrl(sandboxBaseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(httpPool)
                                .responseTimeout(httpTimeout)
                ))
                .build();
    }
}
