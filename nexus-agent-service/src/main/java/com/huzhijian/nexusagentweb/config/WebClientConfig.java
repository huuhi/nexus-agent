package com.huzhijian.nexusagentweb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * 说明:
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(){
        ConnectionProvider httpPool = ConnectionProvider.builder("http_pool")
                .disposeTimeout(Duration.ofSeconds(20))
                .maxConnectionPools(100) // 最大连接100
                .pendingAcquireMaxCount(500) // 队列最大长度
                .pendingAcquireTimeout(Duration.ofSeconds(30)) // 获取连接最大等待数
                .maxIdleTime(Duration.ofSeconds(60)) // 空闲连接 60秒后关闭
                .evictInBackground(Duration.ofMinutes(1))  // 清理后台连接间隔
                .build();
        return WebClient.builder()
                .baseUrl("http://localhost:8000")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create(httpPool)
                ))
                .build();
    }
}
