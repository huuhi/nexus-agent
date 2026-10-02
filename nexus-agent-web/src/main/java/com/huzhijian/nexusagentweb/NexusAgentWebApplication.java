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
//        注意：这里不能再用 System.getenv("BASE_URL") 打日志 ——
//        ① 它在 Spring 容器启动之前执行，拿不到配置文件里的值，会显示成默认值误导人；
//        ② 直接读环境变量会绕开 Spring，配置文件里写的 BASE_URL 读不到。
//        真实生效的地址由 WebClientConfig 在构建 WebClient 时打印（那里才是 Spring 解析后的值）。
        SpringApplication.run(NexusAgentWebApplication.class, args);
    }

}
