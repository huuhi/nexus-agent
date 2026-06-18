package com.huzhijian.nexusagentweb.content;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
public class RedisContent {
    public static final String   EMAIL_CODE_PREFIX="code:";
    public static final String   LONG_MEMORY_STREAM="memory.stream";
    public static final String   LONG_MEMORY_GROUP_KEY="memory";
    public static final long LOCK_TTL=5L;
    public static final long CACHE_NULL_TTL=2L;
    public static final String LOCK_KEY="lock:";
    public static final String CONFIG_KEY="config:";
    public static final long CONFIG_TTL=3L;
}
