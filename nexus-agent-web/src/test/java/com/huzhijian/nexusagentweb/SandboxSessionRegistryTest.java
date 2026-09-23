package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.sandbox.SandboxClient;
import com.huzhijian.nexusagentweb.sandbox.SandboxSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SandboxSessionRegistry 的纯单元测试。
 * <p>
 * 用桩替换 {@link SandboxClient}，因此不会真的创建 E2B 沙盒（那是要计费的）。
 * 验证的是「按会话复用」与「空闲回收」这两个省钱的行为。
 */
class SandboxSessionRegistryTest {

    /** 记录调用的假客户端 */
    private static class StubSandboxClient extends SandboxClient {
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        private int seq = 0;

        StubSandboxClient() {
            super(null);
        }

        @Override
        public Map<String, Object> createBox() {
            String boxId = "box-" + (++seq);
            created.add(boxId);
            return Map.of("message", "沙盒创建成功！", "box_id", boxId);
        }

        @Override
        public Map<String, Object> deleteBox(String boxId) {
            deleted.add(boxId);
            return Map.of("success", true);
        }
    }

    private final StubSandboxClient client = new StubSandboxClient();
    private final AgentProperties properties = new AgentProperties();
    private final SandboxSessionRegistry registry = new SandboxSessionRegistry(client, properties);

    /** 复刻 BoxTool 的用法：acquire 拿沙盒，非复用时登记 */
    private String acquireAndRegister(String sessionKey) {
        Map<String, Object> result = registry.acquire(sessionKey);
        Object boxId = result.get("box_id");
        if (!Boolean.TRUE.equals(result.get("reused")) && boxId != null) {
            registry.register(sessionKey, boxId.toString());
        }
        return String.valueOf(boxId);
    }

    @Test
    @DisplayName("同一会话重复 acquire 复用同一个沙盒，只创建一次")
    void sameSessionReusesBox() {
        String first = acquireAndRegister("s1");
        String second = acquireAndRegister("s1");

        assertEquals(first, second);
        assertEquals(1, client.created.size(), "不应重复创建沙盒（E2B 按量计费）");
    }

    @Test
    @DisplayName("不同会话各自创建沙盒，互不干扰")
    void differentSessionsGetOwnBoxes() {
        String a = acquireAndRegister("s1");
        String b = acquireAndRegister("s2");

        assertEquals(2, client.created.size());
        assertTrue(!a.equals(b));
    }

    @Test
    @DisplayName("release 会销毁沙盒并清掉记录")
    void releaseDestroysAndForgets() {
        String boxId = acquireAndRegister("s1");
        registry.release("s1");

        assertEquals(List.of(boxId), client.deleted);
        assertEquals(0, registry.activeCount());
        // 释放后再要，应当新建
        acquireAndRegister("s1");
        assertEquals(2, client.created.size());
    }

    @Test
    @DisplayName("空闲超过阈值的沙盒会被回收任务销毁")
    void sweepDestroysIdleBoxes() throws Exception {
        properties.getSandbox().setIdleTimeout(Duration.ofMillis(1));
        String boxId = acquireAndRegister("s1");

        Thread.sleep(30);
        registry.sweepIdleSandboxes();

        assertEquals(List.of(boxId), client.deleted, "空闲沙盒应被回收");
        assertEquals(0, registry.activeCount());
    }

    @Test
    @DisplayName("空闲阈值之内的沙盒不会被回收")
    void sweepKeepsFreshBoxes() {
        properties.getSandbox().setIdleTimeout(Duration.ofHours(1));
        acquireAndRegister("s1");

        registry.sweepIdleSandboxes();

        assertTrue(client.deleted.isEmpty(), "还在使用期内的沙盒不应被回收");
        assertEquals(1, registry.activeCount());
    }

    @Test
    @DisplayName("关闭应用时销毁全部沙盒")
    void destroyAllOnShutdown() {
        acquireAndRegister("s1");
        acquireAndRegister("s2");

        registry.destroyAll();

        assertEquals(2, client.deleted.size());
        assertEquals(0, registry.activeCount());
    }

    @Test
    @DisplayName("关闭按会话复用后，acquire 每次都新建")
    void reuseDisabledAlwaysCreates() {
        properties.getSandbox().setReusePerSession(false);
        acquireAndRegister("s1");
        acquireAndRegister("s1");

        // 关闭复用时 register 不会记录，因此第二次仍是新建
        assertEquals(2, client.created.size());
        assertEquals(0, registry.activeCount());
    }
}
