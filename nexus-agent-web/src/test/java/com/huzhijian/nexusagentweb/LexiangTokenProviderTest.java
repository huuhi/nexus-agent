package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import com.huzhijian.nexusagentweb.lexiang.LexiangClient;
import com.huzhijian.nexusagentweb.lexiang.LexiangTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LexiangTokenProvider 的单元测试。
 * <p>
 * 钉的是最容易翻车的那条约束：<b>乐享的 access_token 只有 2 小时有效，
 * 而换 token 的接口限频 20 次 / 10 分钟</b>。如果每轮对话都去换一次 token，
 * 一小时内就能把限频打穿，用户侧表现是"凭证明明是对的却一直报 429"。
 * 所以「缓存必须生效」和「并发时不能重复换」这两条必须钉死。
 * <p>
 * 不访问网络、不连 Redis（mock）。
 */
class LexiangTokenProviderTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final LexiangClient client = mock(LexiangClient.class);

    @SuppressWarnings("unchecked")
    private LexiangTokenProvider provider() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new LexiangTokenProvider(redisTemplate, client);
    }

    @Test
    @DisplayName("缓存命中时不再调乐享换 token")
    void usesCacheWhenPresent() {
        when(valueOps.get(anyString())).thenReturn("cached-token");
        var provider = provider();

        String token = provider.getToken("AK", "SK");

        assertEquals("cached-token", token);
        // 这是本测试类的核心断言：绝不能再打乐享
        verify(client, times(0)).fetchToken(anyString(), anyString());
    }

    @Test
    @DisplayName("缓存未命中时换一次 token，并写入缓存（TTL 留 5 分钟余量）")
    void fetchesAndCachesOnMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(client.fetchToken("AK", "SK")).thenReturn("fresh-token");
        var provider = provider();

        String token = provider.getToken("AK", "SK");

        assertEquals("fresh-token", token);
        verify(client, times(1)).fetchToken("AK", "SK");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), anyString(), ttl.capture());
        // 乐享给 7200 秒，我们存 6900 —— 5 分钟余量是为了避免 token 边界抖动
        assertEquals(LexiangApi.TOKEN_TTL_SECONDS, ttl.getValue().getSeconds());
        assertTrue(ttl.getValue().getSeconds() < 7200, "必须留过期余量，不能卡着 2 小时存");
    }

    @Test
    @DisplayName("Redis 读不到时靠进程内快照兜住，不重复换 token")
    void localSnapshotCoversRedisMiss() {
        // 模拟最坏情况：Redis 一直返回 null（抖动/多主节点不一致）
        when(valueOps.get(anyString())).thenReturn(null);
        when(client.fetchToken("AK", "SK")).thenReturn("fresh-token");
        var provider = provider();

        provider.getToken("AK", "SK");
        provider.getToken("AK", "SK");
        provider.getToken("AK", "SK");

        // 这是本类的核心断言：Redis 挂了也绝不能反复换 token
        verify(client, times(1)).fetchToken("AK", "SK");
    }

    @Test
    @DisplayName("Redis 挂掉时降级为直接换 token，不能让检索完全不可用")
    void degradesWhenRedisUnavailable() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 连接失败"));
        when(client.fetchToken("AK", "SK")).thenReturn("fresh-token");
        var provider = provider();

        String token = provider.getToken("AK", "SK");

        assertEquals("fresh-token", token, "缓存不可用也必须能拿到 token");
    }

    @Test
    @DisplayName("并发请求只换一次 token（不能把乐享限频打穿）")
    void concurrentCallsFetchOnce() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();
        when(client.fetchToken(anyString(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(50);
            return "fresh-token";
        });
        var provider = provider();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                return provider.getToken("AK", "SK");
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, calls.get(), "并发下必须只换一次 token，否则必然撞 20 次/10 分钟 限频");
    }

    @Test
    @DisplayName("凭证缺失时立刻报错，不去打乐享")
    void rejectsBlankCredential() {
        var provider = provider();

        assertThrows(IllegalStateException.class, () -> provider.getToken("", "SK"));
        assertThrows(IllegalStateException.class, () -> provider.getToken("AK", ""));
        verify(client, times(0)).fetchToken(anyString(), anyString());
    }

    @Test
    @DisplayName("evict 后缓存被清掉，下次重新换 token（改凭证要立即生效）")
    void evictClearsCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(client.fetchToken("AK", "SK")).thenReturn("fresh-token");
        var provider = provider();

        provider.getToken("AK", "SK");
        provider.evict("AK");
        // evict 之后必须重新换：否则用户改完凭证仍然拿着旧 token，表现为「改对了还是 401」
        provider.getToken("AK", "SK");

        verify(redisTemplate).delete("lexiang:token:AK");
        verify(client, times(2)).fetchToken("AK", "SK");
    }
}
