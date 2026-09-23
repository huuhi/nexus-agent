package com.huzhijian.nexusagentweb.service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
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
/**
 * ⚠️ 人工集成测试，**默认禁用**。
 * <p>
 * 本类会真实访问外部服务（E2B 云沙盒 / OSS / 大模型 API），因此：
 * 1. 会产生真实费用（如创建 E2B 沙盒）与网络依赖，不适合放进 CI；
 * 2. 结果随外部服务状态波动，失败不代表代码有问题。
 * <p>
 * 需要人工验证时，去掉下面的 @Disabled 再单独运行本类。
 */
@Disabled("人工集成测试：会访问真实外部服务（含计费服务），需要时手动运行")
@Tag("manual")
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
