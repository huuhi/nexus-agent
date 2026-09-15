package com.huzhijian.nexusagentweb.context;

import com.huzhijian.nexusagentweb.domain.UserConfig;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public class UserConfigContextHolder {
    private static final ThreadLocal<UserConfig> tl=new ThreadLocal<>();
    public static void saveConfig(UserConfig config){
        tl.set(config);
    }
    public static void removeConfig(){ tl.remove(); }
    public static UserConfig getUserConfig() {
//        模拟
        String apiConfig= """
                [{"id": "xEzqaQFgFv1", "model": ["deepseek-v4-flash", "deepseek-v4-pro"], "APIKey": "194f315812dbabf110081ed11336d11d751681a6f40d2bcaa6a1e553ff44cdbbf2f8d5f1e7910b898186282e94afac767e7ca36737181c6296fdf5ca17466bab", "baseUrl": "https://api.deepseek.com", "isDefault": true}]
                """;
        String mcpToken="cfdb16f8933178a399376d318a1d6084a5ff6bb1528772388804a0f51c9f316e1399a67123c77cac5d757f774459f849aaf027399a4eb8cb0f4480c809d528f8";
        return UserConfig.builder().llmApiToken(apiConfig).salt("b8109dde1588343f").mcpToken(mcpToken).build();
    }
}
