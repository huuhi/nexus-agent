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
    // SESSION_KEY 已移除：会话与用户的关联不再经过 Redis。
    // 原先用 session:<id> -> userId 缓存（TTL 仅 5 分钟）给聊天记录写库兜底，
    // 过期后会导致聊天记录写不进库；现在该信息由 RunContext 显式传递。
    public static final long CONFIG_TTL=3L;
}
