package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.model.ChatModelFactory;
import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型实例缓存（首字延迟治理）的契约测试。
 * <p>
 * <b>为什么要测</b>：2026-10-05 之前，每轮对话都 {@code new} 一个
 * {@code OpenAiStreamingChatModel}，它内部的 {@code SpringRestClientBuilderFactory}
 * 会顺带新建一整套 HTTP 客户端 + 连接池 —— 连接永远无法复用（每轮重新 DNS/TCP/TLS 握手），
 * 而且上一轮的连接池没人关闭。这个 bug 的表现是「首字慢 + 偶尔卡十几秒」，
 * <b>纯 mock 单测看不见它</b>（不发起真实请求就看不出连接是否复用），
 * 所以只能用「实例是否被复用」这条可断言的契约把它钉住。
 * <p>
 * ⚠️ 本测试只建模型对象，<b>不发任何网络请求</b>。
 */
class ChatModelFactoryCacheTest {

    private static final String URL = "https://api.example.test/v1";
    private static final String KEY = "sk-unit-test-not-a-real-key";

    private static ChatModelFactory factory() {
        return new ChatModelFactory(new ModelCapabilityResolver(new AgentProperties()));
    }

    private static StreamingChatModel build(ChatModelFactory factory, String key, String model, boolean thinking) {
        return factory.build(URL, key, model, 8192, thinking);
    }

    @Test
    void sameConfigReusesSameModelInstance() {
        ChatModelFactory factory = factory();
        StreamingChatModel first = build(factory, KEY, "unit-model", false);
        StreamingChatModel second = build(factory, KEY, "unit-model", false);

        // 同一个「地址 + Key + 模型 + 参数」必须复用同一个实例，
        // 否则每轮对话都会重建 HTTP 客户端与连接池 —— 正是要修的那个问题
        assertSame(first, second, "相同配置应当复用同一个模型实例（即同一个连接池）");
    }

    @Test
    void differentConfigProducesDifferentInstance() {
        ChatModelFactory factory = factory();
        StreamingChatModel base = build(factory, KEY, "unit-model", false);

        // Key 变了（用户换了 API Key）→ 必须用新实例，否则会拿旧凭据去请求
        assertNotSame(base, build(factory, KEY + "-rotated", "unit-model", false));
        // 模型变了 → 新实例
        assertNotSame(base, build(factory, KEY, "another-model", false));
        // 思考开关变了 → 下发的 customParameters 不同，也必须是新实例
        assertNotSame(base, build(factory, KEY, "unit-model", true));
    }

    @Test
    void cacheIsBounded() {
        ChatModelFactory factory = factory();
        // 每个缓存条目都持有一个自己的连接池，缓存必须**有上限**，
        // 否则「多个用户 × 多个模型」会把连接池与连接数拖垮
        for (int i = 0; i < 40; i++) {
            build(factory, KEY, "model-" + i, false);
        }
        assertEquals(32, factory.cachedModelCount(), "缓存必须有上限（32），超出的要被淘汰");
    }

    @Test
    void cacheKeyMustNotContainPlainApiKey() throws Exception {
        ChatModelFactory factory = factory();
        build(factory, KEY, "unit-model", false);

        Field cacheField = ChatModelFactory.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, StreamingChatModel> cache = (Map<String, StreamingChatModel>) cacheField.get(factory);

        assertEquals(1, cache.size());
        // 缓存 key 只存 SHA-256 摘要：明文 Key 一旦进 key，
        // heap dump / 调试器里就能直接捞到用户凭据
        assertTrue(cache.keySet().stream().noneMatch(k -> k.contains(KEY)),
                "缓存键里不允许出现明文 API Key");
    }
}
