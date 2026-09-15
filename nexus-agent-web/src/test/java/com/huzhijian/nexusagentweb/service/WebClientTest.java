package com.huzhijian.nexusagentweb.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/6/15
 * 说明:
 */
@SpringBootTest
public class WebClientTest {
    @Autowired
    private  WebClient client;

    @Test
    public void test(){
        client.get().uri("/box")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .take(1)
                .subscribe(System.out::println);
        // 等待一下让请求发出
        try { Thread.sleep(5000); } catch (Exception e) {}

    }

}
