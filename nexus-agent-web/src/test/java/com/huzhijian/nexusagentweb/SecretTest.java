package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/28
 * 说明:
 */

public class SecretTest {

    @Test
    void getSalt(){

        String apiConfig= """
                [{"id": "xEzqaQFgFv1", "model": ["deepseek-v4-flash", "deepseek-v4-pro"], "APIKey": "194f315812dbabf110081ed11336d11d751681a6f40d2bcaa6a1e553ff44cdbbf2f8d5f1e7910b898186282e94afac767e7ca36737181c6296fdf5ca17466bab", "baseUrl": "https://api.deepseek.com", "isDefault": true}]
                """;
        List<APIConfig> apiConfigs = JSONUtil.toList(apiConfig, APIConfig.class);
        System.out.println(apiConfigs);
        String key1 = KeyGenerators.string().generateKey();
        System.out.println(key1);
        System.out.println(key1.length());
    }

    @Test
    void getAAA(){
        String a= System.getenv("BASE_URL")==null?"http://localhost:8000": java.lang.System.getProperty("BASE_URL");
        System.out.println(a);
    }

    @Test
    public void testDirectConnection() {
        WebClient client = WebClient.builder()
                .baseUrl("http://100.106.145.17:8000")
                .build();

        Flux<String> result = client.get()
                .uri("/box")          // 根据实际接口路径调整
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(10));

        result.subscribe(
                data -> System.out.println("收到: " + data),
                error -> System.err.println("错误: " + error.getMessage())
        );

        // 等待足够时间让请求完成
        try { Thread.sleep(8000); } catch (InterruptedException e) { }
    }
}
