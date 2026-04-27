package com.huzhijian.nexusagentweb.context;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public class UserConfigContextHolder {
    private static final ThreadLocal<Long> tl=new ThreadLocal<>();
    public static void saveConfig(Long userId){
        tl.set(userId);
    }
    public static void removeConfig(){ tl.remove(); }
    public static Long getUserConfig(Long userId) {
        return null;
    }
}
