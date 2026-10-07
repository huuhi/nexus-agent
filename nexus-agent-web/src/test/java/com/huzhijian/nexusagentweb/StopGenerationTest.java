package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.RunCancellationRegistry;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「停止生成」的契约（2026-10-06 新增）。
 *
 * <p>这个功能最要紧的两条不变式，都在这里钉死：
 * <ol>
 *   <li><b>叫停不是错误</b> —— 前端收到的是 {@code stopped}，不是 {@code error}。
 *       发错的话用户会以为「出错了」，而他想要的就是停下来；</li>
 *   <li><b>已生成的内容不能丢</b> —— 由 {@code ChatServiceImpl#persistCancelledAnswer}
 *       补写落库（langchain4j 只在正常完成时写记忆，叫停走不到那条路）。
 *       这里至少守住 Sse 侧：停止时缓冲里的尾部正文必须补发完再关流。</li>
 * </ol>
 */
@DisplayName("停止生成 —— 注册表语义与 SSE 契约")
class StopGenerationTest {

    private static final String RUN_ID = "run-abc123";
    private static final String SESSION_ID = "sess-1";

    @Nested
    @DisplayName("运行登记表")
    class Registry {

        @Test
        @DisplayName("登记后未叫停：isCancelled 为 false")
        void notCancelledByDefault() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(RUN_ID, SESSION_ID);
            assertFalse(registry.isCancelled(RUN_ID));
        }

        @Test
        @DisplayName("叫停后：isCancelled 为 true（流式回调线程靠它决定是否中断）")
        void cancelMarksRun() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(RUN_ID, SESSION_ID);

            assertTrue(registry.cancel(RUN_ID));
            assertTrue(registry.isCancelled(RUN_ID));
        }

        @Test
        @DisplayName("重复叫停幂等：第二次返回 false，不该把已在收尾的运行再标记一遍")
        void cancelIsIdempotent() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(RUN_ID, SESSION_ID);

            assertTrue(registry.cancel(RUN_ID), "第一次：从运行中改为已停止");
            assertFalse(registry.cancel(RUN_ID), "第二次：已经是停止态，不该再改");
        }

        @Test
        @DisplayName("叫停一个不存在的 runId：返回 false 且不留下脏登记")
        void cancelUnknownRun() {
            RunCancellationRegistry registry = new RunCancellationRegistry();

            assertFalse(registry.cancel("never-registered"));
            assertFalse(registry.isCancelled("never-registered"),
                    "查无此运行时不能把它标成已停止 —— 否则同一个 id 复用时会误停新运行");
            assertEquals(0, registry.activeCount(), "不该留下任何登记");
        }

        @Test
        @DisplayName("按会话叫停：反查到当前 runId 并标记")
        void cancelBySession() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(RUN_ID, SESSION_ID);

            assertEquals(RUN_ID, registry.cancelBySession(SESSION_ID));
            assertTrue(registry.isCancelled(RUN_ID));
        }

        @Test
        @DisplayName("会话没有在跑时：返回 null（前端据此提示「没有正在进行的生成」）")
        void cancelBySessionWithNothingRunning() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            assertNull(registry.cancelBySession(SESSION_ID));
            assertNull(registry.cancelBySession(null));
        }

        @Test
        @DisplayName("运行结束要摘登记，否则注册表只增不减")
        void unregisterClears() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(RUN_ID, SESSION_ID);
            assertEquals(1, registry.activeCount());

            registry.unregister(RUN_ID, SESSION_ID);

            assertEquals(0, registry.activeCount());
            assertNull(registry.cancelBySession(SESSION_ID), "摘掉后不该还能按会话叫停");
        }

        @Test
        @DisplayName("runId 为 null 时全链路安全（不 NPE）")
        void nullSafe() {
            RunCancellationRegistry registry = new RunCancellationRegistry();
            registry.register(null, null);

            assertFalse(registry.isCancelled(null));
            assertFalse(registry.cancel(null));
            assertNull(registry.cancelBySession(null));
            registry.unregister(null, null);
        }
    }

    @Nested
    @DisplayName("SSE 侧")
    class Sse {

        @Test
        @DisplayName("叫停发的是 stopped 事件，不是 error（用户主动叫停不是失败）")
        void stoppedIsNotError() {
            Recorder r = recorder();
            r.writeContent("答了一半");
            r.writeStopped(4);

            List<String> names = r.frames.stream().map(SseEvent::getEvent).toList();
            assertEquals(List.of("run", "message", "stopped"), names,
                    "叫停绝不能发 error —— 前端会弹错误提示，而用户只是想停下来");
        }

        @Test
        @DisplayName("stopped 载荷带 partial 字符数（让前端知道回答没写完）")
        void stoppedCarriesPartialLength() {
            Recorder r = recorder();
            r.writeContent("答了一半");
            r.writeStopped(4);

            SseEvent stopped = r.frames.get(r.frames.size() - 1);
            assertEquals("stopped", stopped.getEvent());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) stopped.getData();
            assertNotNull(data);
            assertEquals(4, data.get("partial"));
            assertEquals(RUN_ID, stopped.getRunId(), "runId 在信封层，定位日志靠它");
        }

        @Test
        @DisplayName("叫停时缓冲里没发完的尾部必须补发（否则回答凭空少一截）")
        void flushesTailBeforeStopping() {
            // flush-max-chars 设成极大，正文会一直留在缓冲里，只有点 flush 才发
            Recorder r = new Recorder(new SseEmitter(), false, mock(ChatHistoryListService.class),
                    SESSION_ID, 1L, "你好", RUN_ID, 1_000_000, 1_000_000L);
            r.writeContent("这段还压在缓冲里");
            assertEquals(1, r.frames.size(), "正文还在缓冲，没发");

            r.writeStopped(9);

            assertEquals(List.of("run", "message", "stopped"),
                    r.frames.stream().map(SseEvent::getEvent).toList(),
                    "stopped 之前必须先把缓冲里的正文发出去");
            SseEvent message = r.frames.get(1);
            // ⚠️ SseEvent.data 装的是**原对象**：message 事件是 MessageVO，
            //    不是 Map —— 强转成 Map 会 ClassCastException（这条断言本身就是个坑）
            assertEquals("这段还压在缓冲里", ((MessageVO) message.getData()).getContent());
        }

        @Test
        @DisplayName("已结束的流再叫停是空操作（不能重复发事件）")
        void stoppedAfterFinishIsNoop() {
            Recorder r = recorder();
            r.writeContent("正文");
            r.finish();
            int before = r.frames.size();

            r.writeStopped(2);

            assertEquals(before, r.frames.size(), "流已关闭后再叫停不应该再发任何帧");
        }
    }

    /** 复用 SseContractTest 的做法：覆写 dispatch 把帧收集起来，而不是真发 SSE */
    private static class Recorder extends SseResponseConverter {
        final List<SseEvent> frames = new ArrayList<>();

        Recorder(boolean isNewSession, ChatHistoryListService history) {
            this(new SseEmitter(), isNewSession, history);
        }

        Recorder(SseEmitter emitter, boolean isNewSession, ChatHistoryListService history) {
            this(emitter, isNewSession, history, SESSION_ID, 1L, "你好", RUN_ID, 1, 1L);
        }

        Recorder(SseEmitter emitter, boolean isNewSession, ChatHistoryListService history,
                 String sessionId, Long userId, String message, String runId,
                 Integer flushChars, Long flushMillis) {
            // 本测试不涉及工具可见性：传 null = 不隐藏任何工具（与改动前行为一致）
            super(emitter, isNewSession, history, sessionId, userId, message, runId,
                    flushChars, flushMillis, null);
            start();
        }

        @Override
        protected void dispatch(SseEvent event) {
            frames.add(event);
        }
    }

    private Recorder recorder() {
        return new Recorder(new SseEmitter(), false, mock(ChatHistoryListService.class));
    }
}
