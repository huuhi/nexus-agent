package com.huzhijian.nexusagentweb.factory;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * 用户密钥加解密工厂。
 * <p>
 * <b>2026-10-03 改动</b>：主密钥原先只从 {@code System.getenv("API_KEY_SECRET")} 读，
 * 这条路径<b>绕开 Spring</b> —— 写在 yml / {@code .env.properties} / 面板配置里的一律读不到，
 * 表现为「明明在配置文件里写了，却报 secret 为空」。
 * 现在改为：**Spring 配置项 {@code nexus.agent.api-key-secret} 优先，环境变量 {@code API_KEY_SECRET} 兜底**，
 * 由 {@code RuntimeSecretInitializer} 在启动时把配置值注入。
 * <p>
 * <b>为什么改成惰性解析</b>：原实现是 {@code private static final String SECRET_KEY = resolve();}，
 * 缺值时就在<b>类初始化</b>阶段抛 {@code ExceptionInInitializerError}，
 * 之后每次访问都会变成 {@code NoClassDefFoundError: Could not initialize class ...} ——
 * 错误信息与真实原因完全脱节（昨天 JwtUtil 就是这样把全站拖成 500 的）。
 * 改成惰性后：
 * <ol>
 *   <li>缺值时只在<b>真正用到加解密的那一次</b>抛异常，且信息可读；</li>
 *   <li>不依赖 Spring 容器的初始化顺序，纯单测里没注入也能跑（回退环境变量）；</li>
 *   <li>注入器塞入新值后，下一次访问会重新解析。</li>
 * </ol>
 */
public class EncryptorFactory {

    /** 由 Spring 注入的配置值（{@code nexus.agent.api-key-secret}） */
    private static volatile String configuredSecret;

    /** 最终生效的主密钥；null 表示还没解析过 */
    private static volatile String secretKey;

    private EncryptorFactory() {
    }

    /**
     * 由 {@code RuntimeSecretInitializer} 在容器启动时调用。
     * 传 null / 空白表示「没配」，回退环境变量 {@code API_KEY_SECRET}。
     */
    public static void setConfiguredSecret(String secret) {
        configuredSecret = (secret == null || secret.isBlank()) ? null : secret;
//        置空后下一次访问会重新解析，保证注入的值立刻生效
        secretKey = null;
    }

    /**
     * @param salt 每个用户独立的盐值，不能为空
     * @return 该用户专属的加解密器
     */
    public static TextEncryptor text(String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException("用户盐值（salt）为空，无法加解密：说明 user_config 记录不完整。");
        }
        return Encryptors.text(secretKey(), salt);
    }

    private static String secretKey() {
        String key = secretKey;
        if (key == null) {
            synchronized (EncryptorFactory.class) {
                key = secretKey;
                if (key == null) {
                    key = resolveSecretKey();
                    secretKey = key;
                }
            }
        }
        return key;
    }

    private static String resolveSecretKey() {
        String key = configuredSecret;
        if (key == null || key.isBlank()) {
            key = System.getenv("API_KEY_SECRET");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "缺少用户 API Key 的加密主密钥。"
                            + "请在 nexus.agent.api-key-secret 配置项或 API_KEY_SECRET 环境变量中配置后再启动。");
        }
        return key;
    }
}
