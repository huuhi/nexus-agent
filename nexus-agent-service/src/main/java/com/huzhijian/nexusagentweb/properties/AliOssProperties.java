package com.huzhijian.nexusagentweb.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "spring.aliyun")
@Data
public class AliOssProperties {
    private String endpoint;
    private String bucketName;
    private String region;
    private String imageDir;
    private String fileDir;

    /**
     * OSS 访问凭证（AccessKey）。
     * <p>
     * 2026-10-02 补：原先凭证由 SDK 的 {@code EnvironmentVariableCredentialsProvider}
     * 直接读 {@code System.getenv()}，而 endpoint / bucket / region 却来自本配置类 ——
     * 一半走 Spring、一半走环境变量，结果「配置里明明写了却读不到」。
     * 现在两者都可以：配了这两个字段就用它们，没配才回退环境变量。
     * 生产 yml 里默认写成 {@code ${OSS_ACCESS_KEY_ID:}}，即"环境变量优先、配置可覆盖"。
     */
    private String accessKeyId;
    private String accessKeySecret;
}