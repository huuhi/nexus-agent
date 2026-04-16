package com.huzhijian.nexusagentweb;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.huzhijian.nexusagentweb.mapper")
public class NexusAgentWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusAgentWebApplication.class, args);
    }

}
