package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolCallGuard 的纯单元测试：验证「重复调用拦截」的判定与边界。
 * <p>
 * 不依赖 Spring、不访问网络。
 */
class ToolCallGuardTest {

    private ToolCallGuard guard(Duration window, int threshold) {
        AgentProperties props = new AgentProperties();
        props.getTools().setDuplicateWindow(window);
        props.getTools().setDuplicateThreshold(threshold);
        return new ToolCallGuard(props);
    }

    @Test
    @DisplayName("阈值 2：前两次放行，第三次相同调用被拦截")
    void blocksThirdIdenticalCall() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 2);
        String fp = ToolCallGuard.fingerprint("ls -l /tmp");

        assertNull(guard.intercept("s1", "execute_cmd", fp), "第 1 次应放行");
        assertNull(guard.intercept("s1", "execute_cmd", fp), "第 2 次应放行");
        Map<String, Object> blocked = guard.intercept("s1", "execute_cmd", fp);
        assertNotNull(blocked, "第 3 次应被拦截");
    }

    @Test
    @DisplayName("拦截结果是结构化结果：success=false + 错误码 + 提示")
    void blockedPayloadIsStructured() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        String fp = ToolCallGuard.fingerprint("whoami");
        guard.intercept("s1", "execute_cmd", fp);

        Map<String, Object> blocked = guard.intercept("s1", "execute_cmd", fp);

        assertEquals(false, blocked.get("success"));
        assertEquals(ToolCallGuard.CODE_DUPLICATE_CALL, blocked.get("errorCode"));
        assertTrue(String.valueOf(blocked.get("message")).contains("execute_cmd"));
        assertTrue(String.valueOf(blocked.get("hint")).contains("不要重复调用"));
    }

    @Test
    @DisplayName("参数不同则互不影响（换参数重试是正当行为，不能误拦）")
    void differentArgsAreIndependent() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        guard.intercept("s1", "execute_cmd", ToolCallGuard.fingerprint("ls"));

        assertNull(guard.intercept("s1", "execute_cmd", ToolCallGuard.fingerprint("pwd")),
                "换了参数应放行");
    }

    @Test
    @DisplayName("不同会话互不影响")
    void differentSessionsAreIndependent() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        String fp = ToolCallGuard.fingerprint("ls");
        guard.intercept("session-a", "execute_cmd", fp);

        assertNull(guard.intercept("session-b", "execute_cmd", fp), "另一会话应放行");
    }

    @Test
    @DisplayName("不同工具互不影响")
    void differentToolsAreIndependent() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        String fp = ToolCallGuard.fingerprint("x");
        guard.intercept("s1", "execute_cmd", fp);

        assertNull(guard.intercept("s1", "execute_code", fp), "另一工具应放行");
    }

    @Test
    @DisplayName("超出窗口后重新计数（滑动窗口语义）")
    void windowExpiryResetsCount() throws InterruptedException {
        ToolCallGuard guard = guard(Duration.ofMillis(80), 1);
        String fp = ToolCallGuard.fingerprint("date");

        assertNull(guard.intercept("s1", "execute_cmd", fp));
        assertNotNull(guard.intercept("s1", "execute_cmd", fp), "窗口内第二次应被拦");

        Thread.sleep(150); // 等窗口滑过

        assertNull(guard.intercept("s1", "execute_cmd", fp), "窗口过期后应重新放行");
    }

    @Test
    @DisplayName("threshold<=0 时治理关闭，永不拦截")
    void zeroThresholdDisablesGuard() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 0);
        String fp = ToolCallGuard.fingerprint("ls");

        for (int i = 0; i < 5; i++) {
            assertNull(guard.intercept("s1", "execute_cmd", fp), "第 " + (i + 1) + " 次也应放行");
        }
    }

    @Test
    @DisplayName("会话为 null 时不抛异常（走匿名桶）")
    void nullSessionDoesNotThrow() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        String fp = ToolCallGuard.fingerprint("ls");

        assertNull(guard.intercept(null, "execute_cmd", fp));
        assertNotNull(guard.intercept(null, "execute_cmd", fp), "同一匿名桶的第二次应被拦");
    }

    @Test
    @DisplayName("interceptText：放行返回 null，拦截返回 JSON 文本")
    void interceptTextReturnsJson() {
        ToolCallGuard guard = guard(Duration.ofSeconds(60), 1);
        String fp = ToolCallGuard.fingerprint("hi");

        assertNull(guard.interceptText("s1", "rag_search", fp));
        String blocked = guard.interceptText("s1", "rag_search", fp);

        assertNotNull(blocked);
        assertTrue(blocked.startsWith("{") && blocked.contains(ToolCallGuard.CODE_DUPLICATE_CALL),
                "应是含错误码的 JSON：" + blocked);
    }

    @Test
    @DisplayName("fingerprint 不会把不同参数组合拼成同一个字符串")
    void fingerprintAvoidsAmbiguity() {
        // 若用无分隔符拼接，("ab","c") 与 ("a","bc") 会得到同一个 key → 误判为重复
        assertNotEquals(ToolCallGuard.fingerprint("ab", "c"), ToolCallGuard.fingerprint("a", "bc"));
    }

    @Test
    @DisplayName("sweep 清理过期条目，避免 Map 无限增长")
    void sweepRemovesExpiredKeys() throws InterruptedException {
        ToolCallGuard guard = guard(Duration.ofMillis(50), 1);
        guard.intercept("s1", "execute_cmd", ToolCallGuard.fingerprint("ls"));
        assertEquals(1, guard.trackedKeys());

        Thread.sleep(120);
        guard.sweep();

        assertEquals(0, guard.trackedKeys(), "过期条目应被清理");
    }
}
