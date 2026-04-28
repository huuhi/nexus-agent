package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.keygen.KeyGenerators;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/28
 * 说明:
 */

public class SecretTest {

    @Test
    void getSalt(){

        String apiConfig= """
                [{"id": "xEzqaQFgFv1", "model": ["deepseek-v4-flash", "deepseek-v4-pro"], "APIKey": "194f315812dbabf110081ed11336d11d751681a6f40d2bcaa6a1e553ff44cdbbf2f8d5f1e7910b898186282e94afac767e7ca36737181c6296fdf5ca17466bab", "baseUrl": "https://api.deepseek.com", "isDefault": true}]
                """;
        List<APIConfig> apiConfigs = JSONUtil.toList(apiConfig, APIConfig.class);
        System.out.println(apiConfigs);
        String key1 = KeyGenerators.string().generateKey();
        System.out.println(key1);
        System.out.println(key1.length());
    }
}
