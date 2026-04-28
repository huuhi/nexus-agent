package com.huzhijian.nexusagentweb.factory;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/28
 * 说明:
 */
public class EncryptorFactory {
    private static final String SECRET_KEY=System.getenv("API_KEY_SECRET");

    public static TextEncryptor text(String salt){
        return  Encryptors.text(SECRET_KEY,salt);
    }
}
