package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把 Spring 配置里的密钥注入给两个 static 工具类。
 * <p>
 * <b>为什么需要这个类</b>：{@link JwtUtil} 与 {@link EncryptorFactory} 都是 static 工具，
 * 历史实现直接 {@code System.getenv(...)} 读密钥 —— 这条路径<b>绕开 Spring</b>，
 * 导致写在 yml / {@code .env.properties} / 1Panel 面板配置里的值<b>一律读不到</b>，
 * 表现为「明明配了，日志却说没配」（2026-10-03 线上就是这样）。
 * <p>
 * 现在两者都走 Spring：配置项优先、同名环境变量兜底（兜底写在 {@code @Value} 的默认值里，
 * 环境变量也是 Spring 的一个属性源，所以两者都不需要再调 {@code System.getenv}）。
 * <p>
 * <b>安全</b>：日志只打「有没有值 + 长度」，<b>绝不打印密钥本身</b>。
 */
@Slf4j
@Component
public class RuntimeSecretInitializer {

    /**
     * JWT 签名密钥。支持任意字符串（不再强制 Base64，短串会自动派生，见 JwtUtil）。
     */
    @Value("${nexus.agent.jwt-secret:${JWT_SECRET:}}")
    private String jwtSecret;

    /**
     * 用户 API Key 的加密主密钥。
     */
    @Value("${nexus.agent.api-key-secret:${API_KEY_SECRET:}}")
    private String apiKeySecret;

    @PostConstruct
    public void init() {
        applyJwt();
        applyApiKey();
    }

    private void applyJwt() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.warn("未配置 nexus.agent.jwt-secret（也没有 JWT_SECRET 环境变量）——"
                    + "将随机生成签名密钥，**应用重启后所有已签发 token 立即失效，用户需要重新登录**。"
                    + "生产环境务必配置。");
            return;
        }
        JwtUtil.setConfiguredSecret(jwtSecret);
        log.info("已加载 JWT 签名密钥（来源：nexus.agent.jwt-secret 或 JWT_SECRET，长度 {}）", jwtSecret.length());
    }

    private void applyApiKey() {
        if (apiKeySecret == null || apiKeySecret.isBlank()) {
//            这里不抛异常：EncryptorFactory 是惰性解析的，缺值时会在真正加解密的那一次报错，
//            比在启动期用 ExceptionInInitializerError 把整个类搞成不可用来得好排查。
            log.warn("未配置 nexus.agent.api-key-secret（也没有 API_KEY_SECRET 环境变量）——"
                    + "用户自带 API Key 将无法加密存储，第一次保存配置时才会报错。");
            return;
        }
        EncryptorFactory.setConfiguredSecret(apiKeySecret);
        log.info("已加载用户 API Key 加密主密钥（来源：nexus.agent.api-key-secret 或 API_KEY_SECRET，长度 {}）",
                apiKeySecret.length());
    }
}
