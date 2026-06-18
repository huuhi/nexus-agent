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
    void testJsonToAPI(){
        String josn= """
                [{"id":"06jxtVNuVD5","name":"DeepSeek","APIKey":"4c7af864b20dd83ce0c45c26b6f290133949e528d9dd868da88f96f52a595aaf8e6716ac6b52aa0b8cd75888316253309d1aa2850d339bb1dfb08ce643359ffb","baseUrl":"https://api.deepseek.com","model":[{"name":"deepseek-v4-flash","type":"CHAT"}],"isDefault":false},{"id":"q4Jr63LBqW9","name":"阿里云","APIKey":"9644b4b8b5ccbea1dc46a96341ab784c1a4010d72d36641f5b86b878ad60347eaf6c3957f8beb36dd4a02d13ba95aac85fbfc0e94c842ef4624adaa5e76bab64065819df7f83ff9328051d9910b84dafe64706368228e365779aa0afe95e64c477fb67ec1ac4b0317de648ab97f24b367443c8427af861e267c0700dab0046128e13a2a1c328d49a43c9385877dc02d0","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","model":[{"name":"qwen3.7-max","type":"CHAT"},{"name":"text-embedding-v4","type":"EMBEDDING"}],"isDefault":true}]
                """;
        String josn2= """
                [{"id": "xEzqaQFgFv1", "model": ["deepseek-v4-flash", "deepseek-v4-pro"], "APIKey": "194f315812dbabf110081ed11336d11d751681a6f40d2bcaa6a1e553ff44cdbbf2f8d5f1e7910b898186282e94afac767e7ca36737181c6296fdf5ca17466bab", "baseUrl": "https://api.deepseek.com", "isDefault": true}]
                """;
        List<APIConfig> apiConfigs = JSONUtil.toList(josn2, APIConfig.class);
        System.out.println(apiConfigs);
    }


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
