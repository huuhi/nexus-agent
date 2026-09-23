package com.huzhijian.nexusagentweb.factory;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/28
 * 说明: 用户密钥加解密工厂。
 * <p>
 * 主密钥来自环境变量 {@code API_KEY_SECRET}，启动时校验，缺失直接 fail-fast，
 * 避免运行期才抛 NPE（历史实现把 getenv 结果直接赋给 static final，缺变量时为 null，
 * 直到用户第一次保存配置才炸）。
 */
public class EncryptorFactory {

    private static final String SECRET_KEY = resolveSecretKey();

    private EncryptorFactory() {
    }

    private static String resolveSecretKey() {
        String key = System.getenv("API_KEY_SECRET");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 API_KEY_SECRET（用户 API Key 的加密主密钥）。"
                            + "请在启动环境或 application-*.yml 中配置后再启动。");
        }
        return key;
    }

    /**
     * @param salt 每个用户独立的盐值，不能为空
     * @return 该用户专属的加解密器
     */
    public static TextEncryptor text(String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException("用户盐值（salt）为空，无法加解密：说明 user_config 记录不完整。");
        }
        return Encryptors.text(SECRET_KEY, salt);
    }
}
