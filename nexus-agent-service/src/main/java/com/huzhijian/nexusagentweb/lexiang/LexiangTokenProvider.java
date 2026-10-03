package com.huzhijian.nexusagentweb.lexiang;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 乐享 access_token 的获取与缓存。
 * <p>
 * <b>为什么必须缓存</b>：乐享的 access_token 只有 <b>2 小时</b>有效，
 * 而换 token 的接口<b>限频 20 次 / 10 分钟</b>。对话每轮都会调检索，
 * 如果每次都重新换 token，一小时就能把限频打穿，用户侧表现是
 * 「突然开始报 429，凭证看起来是对的但就是用不了」。
 * <p>
 * <b>缓存策略</b>：
 * <ul>
 *   <li>key 按 appKey 维度（同一个用户的同一个 AppKey 共用一份 token）</li>
 *   <li>TTL 取 {@code 2h - 5min}，留 5 分钟余量避免边界抖动</li>
 *   <li>Redis 不可用时<b>降级为直接换 token</b>（宁可撞限频也不能让检索完全不可用）</li>
 *   <li>进程内再叠一层锁，避免同一实例多线程同时打乐享换 token</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LexiangTokenProvider {

    private static final String KEY_PREFIX = "lexiang:token:";

    /**
     * 同一实例内避免并发重复换 token 的锁。
     * <p>
     * 虚拟线程已开启，ReentrantLock 足够（临界区只有一次 HTTP 调用）。
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 进程内 token 快照，key = appKey。
     * <p>
     * <b>为什么有了 Redis 缓存还要这一层</b>：锁只能保证「一个一个来」，
     * 保证不了「只换一次」—— 后到的线程拿到锁后如果缓存读不到（比如 Redis 抖动、
     * 或刚写的值还没落到别的主节点），仍会再换一次。乐享限频只有
     * 20 次/10 分钟，多实例部署时这个缺口会放大成偶发的 429。
     * 本地快照把这个窗口彻底关掉：拿到过 token 就直接复用，不碰 Redis。
     * <p>
     * 用 Caffeine 语义的手写实现即可（项目已引 Redis，不必为此再加依赖）。
     */
    private final Map<String, CachedToken> localCache = new ConcurrentHashMap<>();

    private final StringRedisTemplate stringRedisTemplate;

    private final LexiangClient client;

    /**
     * 进程内缓存条目。{@code expiresAt} 留了 5 分钟余量（与 Redis 侧一致）。
     */
    private record CachedToken(String token, long expiresAt) {
        boolean usable() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    /**
     * 取一个可用的 access_token，优先走进程内快照 → Redis → 真实请求。
     *
     * @throws IllegalStateException 凭证错误或撞上限频（消息可直接透给用户）
     */
    public String getToken(String appKey, String appSecret) {
        if (StrUtil.isBlank(appKey) || StrUtil.isBlank(appSecret)) {
            throw new IllegalStateException("乐享凭证未配置完整：请先在设置里保存 AppKey 与 AppSecret。");
        }
        String cacheKey = KEY_PREFIX + appKey;

        // 第 1 层：进程内快照。最快，且完全不受 Redis 抖动影响
        CachedToken local = localCache.get(appKey);
        if (local != null && local.usable()) {
            return local.token();
        }

        // 第 2 层：Redis（多实例共享）
        String cached = readCache(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            rememberLocal(appKey, cached);
            return cached;
        }

        // 三层都未命中 —— 串行化后去乐享换
        lock.lock();
        try {
            // Double-Check：等锁期间可能已经被别的线程填好了
            local = localCache.get(appKey);
            if (local != null && local.usable()) {
                return local.token();
            }
            cached = readCache(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                rememberLocal(appKey, cached);
                return cached;
            }
            String fresh = client.fetchToken(appKey, appSecret);
            rememberLocal(appKey, fresh);
            writeCache(cacheKey, fresh);
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 主动作废缓存的 token。
     * <p>
     * 用户在设置里重新保存凭证时调用 —— 否则旧 token 还会被继续使用，
     * 表现为「我明明改对了还是 401」。
     */
    public void evict(String appKey) {
        if (StrUtil.isBlank(appKey)) {
            return;
        }
        // 本地快照也必须清：否则用户改完凭证，进程内还拿着旧 token，
        // 表现为「我明明改对了还是 401」
        localCache.remove(appKey);
        String cacheKey = KEY_PREFIX + appKey;
        try {
            stringRedisTemplate.delete(cacheKey);
            log.debug("已作废乐享 token 缓存：{}", cacheKey);
        } catch (Exception e) {
            log.warn("作废乐享 token 缓存失败（忽略）：{}", e.getMessage());
        }
    }

    private void rememberLocal(String appKey, String token) {
        localCache.put(appKey, new CachedToken(token,
                System.currentTimeMillis() + LexiangApi.TOKEN_TTL_SECONDS * 1000));
    }

    private String readCache(String cacheKey) {
        try {
            return stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            // Redis 挂了就当没缓存，继续走真实请求 —— 不能因为缓存不可用就整个功能挂掉
            log.warn("读取乐享 token 缓存失败，降级为直接换 token：{}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String cacheKey, String token) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(cacheKey, token, Duration.ofSeconds(LexiangApi.TOKEN_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("写入乐享 token 缓存失败（下次将重新换 token）：{}", e.getMessage());
        }
    }
}
