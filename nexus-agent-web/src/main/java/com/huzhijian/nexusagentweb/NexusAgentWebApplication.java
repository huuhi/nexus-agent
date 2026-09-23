package com.huzhijian.nexusagentweb;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.huzhijian.nexusagentweb.mapper")
public class NexusAgentWebApplication {

    public static void main(String[] args) {
        String baseUrl = System.getenv().getOrDefault("BASE_URL", "http://localhost:8000");
        log.info("启动中，沙盒服务地址 BASE_URL: {}", baseUrl);
        SpringApplication.run(NexusAgentWebApplication.class, args);
    }

}
