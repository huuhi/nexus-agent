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

    /**
     * 🔴 解密用户凭据并**校验结果**（2026-10-07 新增）。
     * <p>
     * <b>为什么不能直接用 {@link #text(String)} 的 decrypt</b>：Spring 的
     * {@code Encryptors.text()} 用 AES-CBC + 随机 IV，<b>解密不校验完整性</b> ——
     * 主密钥不对时它<b>不会抛异常</b>，而是解出一串乱码。这串乱码随后被当成 API Key
     * 原样发给厂商，厂商只回一句 {@code 401 Invalid API Key}。
     * 于是「保存时用的主密钥」与「运行时用的主密钥」不一致这个真实原因被完全掩盖，
     * 表现为「我没改 Key 啊」「我换了个新 Key 还是 401」（2026-10-07 线上就是这个症状）。
     * <p>
     * 所以这里在解密后做一次**形状校验**：真实凭据必然是可打印 ASCII
     * （各家 API Key 都是字母数字 + 少量符号），解出乱码时几乎必然含非 ASCII 或控制字符。
     * 不通过就抛<b>带明确原因</b>的异常，绝不让乱码流到出网请求里。
     *
     * @param salt   用户盐值
     * @param cipher 密文
     * @param what   凭据用途（"API Key" / "MCP Token" / "乐享 AppSecret"），只用于报错文案
     * @return 解密后的明文（已校验）
     * @throws IllegalStateException 解密失败，或解出来的东西明显不是有效凭据
     */
    public static String decryptChecked(String salt, String cipher, String what) {
        if (cipher == null || cipher.isBlank()) {
            throw new IllegalStateException(what + " 为空：该配置没有保存过凭据。");
        }
        String plain;
        try {
            plain = text(salt).decrypt(cipher);
        } catch (Exception e) {
//            密文结构不对（长度/填充错误）—— 多半就是主密钥不匹配或密文被改过
            throw new IllegalStateException(what + " 解密失败（" + e.getClass().getSimpleName() + "）："
                    + "主密钥（nexus.agent.api-key-secret / API_KEY_SECRET）很可能与加密时不是同一把，"
                    + "或密文已损坏。请核对主密钥后重新保存一次配置。", e);
        }
        if (plain == null || plain.isBlank()) {
            throw new IllegalStateException(what + " 解密结果为空：主密钥或盐值可能与加密时不一致。");
        }
        String bad = describeWhyNotCredential(plain);
        if (bad != null) {
            throw new IllegalStateException(what + " 解密结果异常（" + bad + "）："
                    + "几乎可以确定是主密钥与加密时不是同一把（解密不校验完整性，会静默产出乱码）。"
                    + "请核对 nexus.agent.api-key-secret / API_KEY_SECRET 后重新保存配置。");
        }
        return plain;
    }

    /**
     * 判断明文是否"明显不是凭据"。
     *
     * @return null = 看着正常；非 null = 异常原因描述
     */
    private static String describeWhyNotCredential(String plain) {
//        替换字符 U+FFFD 是「字节流被按错误编码解码」的典型产物，AES 解错时高发
        if (plain.indexOf('\uFFFD') >= 0) {
            return "含替换字符 U+FFFD";
        }
        int nonPrintable = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                nonPrintable++;
            }
        }
        if (nonPrintable > 0) {
            return "含 " + nonPrintable + " 个非可打印字符（总长 " + plain.length() + "）";
        }
        return null;
    }

    /**
     * 主密钥的短指纹（SHA-256 前 12 位十六进制）。
     * <p>
     * 只用于**比对两次启动是不是同一把密钥**，不可逆推出密钥本身，可以安全打进日志。
     */
    private static String fingerprint(String secret) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
//            SHA-256 是 JDK 强制实现的算法，到不了这里
            return "unknown";
        }
    }

    private static String secretKey() {
        String key = secretKey;
        if (key == null) {
            synchronized (EncryptorFactory.class) {
                key = secretKey;
                if (key == null) {
                    key = resolveSecretKey();
                    secretKey = key;
//                    2026-10-07：打主密钥的**指纹**（不是密钥本身）。
//                    用途：判断「保存凭据时」与「使用凭据时」是不是同一把主密钥 ——
//                    不一致时解密会静默产出乱码、厂商只回 401，没有指纹根本无从比对。
//                    运维只需 grep "主密钥指纹" 看两次启动的值是否相同。
                    log.info("主密钥指纹={}（长度 {}，来源：{}）",
                            fingerprint(key), key.length(),
                            configuredSecret != null && !configuredSecret.isBlank()
                                    ? "nexus.agent.api-key-secret" : "环境变量 API_KEY_SECRET");
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
