package com.huzhijian.nexusagentweb.context;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public class UserContextHolder {
    private static final ThreadLocal<Long> tl=new ThreadLocal<>();

    public static void saveId(Long userId){
        tl.set(userId);
    }
    public static void removeUserId(){ tl.remove(); }
    public static Long getUserId() {
        return 1L;
    }
}
