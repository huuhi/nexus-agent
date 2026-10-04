package com.huzhijian.nexusagentweb.factory;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        assertStrongEnough(key);
        return key;
    }

    /**
     * 主密钥强度下限。
     * <p>
     * <b>为什么要卡这一条</b>：这里的主密钥是<b>所有用户全部凭据</b>的唯一根 ——
     * 一旦它被猜到或泄漏到 git 历史里，攻击者可以离线解开
     * {@code user_config} 里所有用户的 LLM Key 与 MCP Token（密文本身也在同一个库里）。
     * Spring 的 {@code Encryptors.text(secret, salt)} 用 PBKDF2 从这个字符串派生 AES 密钥，
     * 所以它的熵值就是整条链的熵值下限。
     * <p>
     * 门槛定 16 是权衡：太短会误伤真实配置，但
     * {@code API_KEY_SECRET=123456} / {@code nexus-agent} 这类随手写的值必须挡住。
     * <p>
     * 这里刻意<b>不阻断启动</b>，只打 ERROR：密钥是历史存量，
     * 一上线就抛异常会导致「换密钥前应用完全起不来」，那是更大的事故。
     * 换成日志后可以让运维先确认影响面，再决定何时强制。
     */
    private static final int MIN_SECRET_LENGTH = 16;

    private static void assertStrongEnough(String key) {
        if (key.length() < MIN_SECRET_LENGTH) {
            log.error("""

                    ⚠️⚠️ 用户凭据加密主密钥过短（{} 字符 < 建议 {} 字符）⚠️⚠️
                    这把密钥是**所有用户全部 LLM API Key / MCP Token 的唯一根**。
                    一旦被猜到或泄漏，user_config 表里的密文可被批量离线解密。
                    攻击者不需要攻破数据库，只要拿到密文即可。
                    请立即更换为至少 {} 位的随机串，并执行密钥轮换（现有密文需用新密钥重新加密）。
                    如果这是本地开发环境、且数据库里只有你自己的测试 Key，可忽略本条告警。
                    """, key.length(), MIN_SECRET_LENGTH, MIN_SECRET_LENGTH);
        }
    }
}
