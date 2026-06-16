package com.huzhijian.nexusagentweb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.huzhijian.nexusagentweb.mapper")
public class NexusAgentWebApplication {

    public static void main(String[] args) {
        String baseUrl = System.getenv("BASE_URL");
        System.out.println("baseUrl:" + baseUrl);
        SpringApplication.run(NexusAgentWebApplication.class, args);
    }

}
