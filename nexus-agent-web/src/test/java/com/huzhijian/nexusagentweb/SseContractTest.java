package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.em.SseEventType;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialThinking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SSE 契约 v2（P2-5）的纯单元测试。
 * <p>
 * 覆盖三件以前做不到的验证：
 * <ol>
 *   <li>事件名统一小写（v1 里工具事件是全大写枚举值，按 `tool_execution` 监听永远收不到）；</li>
 *   <li>每帧都有 `seq`（从 1 递增）与 `runId`，可据此判断丢帧；</li>
 *   <li>首帧就是 `run`，前端在整段回答开始前就能拿到 sessionId。</li>
 * </ol>
 * 通过覆写 {@link SseResponseConverter#dispatch} 抓帧 —— 不去 mock
 * `SseEmitter` 的内部机制（它把事件转成 `Set<DataWithMediaType>`，外部读不回来）。
 * <p>
 * ⚠️ 缓冲参数刻意设得极大（关掉自动冲刷），让帧序**完全确定**：
 * 只在「类型切换」与「工具/产物/结束/报错前的强制冲刷」时发帧，不受 60ms 计时影响。
 * <p>
 * 不依赖 Spring 上下文、不访问网络。
 */
class SseContractTest {

    private static final String RUN_ID = "a1b2c3d4e5f6";
    /** 关掉自动冲刷：只在类型切换 / 强制冲刷时发帧 */
    private static final int NEVER_FLUSH_CHARS = Integer.MAX_VALUE;
    private static final long NEVER_FLUSH_MILLIS = 1_000_000L;

    /** 记录所有发出的帧；不真正写 SseEmitter */
    private static class Recorder extends SseResponseConverter {
        final List<SseEvent> frames = new ArrayList<>();

        Recorder(boolean isNewSession, ChatHistoryListService history) {
            super(new SseEmitter(), isNewSession, history, "sess-1", 1L, "你好", RUN_ID,
                    NEVER_FLUSH_CHARS, NEVER_FLUSH_MILLIS);
        }

        @Override
        protected void dispatch(SseEvent event) {
            frames.add(event);
        }
    }

    private Recorder recorder() {
        return recorder(false, mock(ChatHistoryListService.class));
    }

    private Recorder recorder(boolean isNewSession, ChatHistoryListService history) {
        return new Recorder(isNewSession, history);
    }

    @Test
    @DisplayName("第一个事件之前必定有 run 首帧，带 runId 与 sessionId（不用等到流结束）")
    void firstFrameIsRun() {
        Recorder r = recorder(true, mock(ChatHistoryListService.class));

        assertEquals(0, r.frames.size(), "构造时不发帧（构造器不做 I/O）");

        r.start();

        assertEquals(1, r.frames.size());
        SseEvent first = r.frames.get(0);
        assertEquals(SseEventType.RUN.getValue(), first.getEvent());
        assertEquals(1, first.getSeq(), "首帧 seq 必须是 1");
        assertEquals(RUN_ID, first.getRunId());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) first.getData();
        assertEquals("sess-1", data.get("sessionId"));
        assertEquals(true, data.get("isNewSession"));
    }

    @Test
    @DisplayName("run 首帧只发一次：显式 start 后再发事件也不会重复下发")
    void runFrameSentOnlyOnce() {
        Recorder r = recorder();
        r.start();
        r.writeContent("一");
        r.writeContent("二");
        r.writeToolResult(ToolExecutionRequest.builder().id("id-1").name("get_date").arguments("{}").build(),
                false, "ok");
        r.finish();

        assertEquals(1, r.frames.stream().filter(e -> "run".equals(e.getEvent())).count(),
                "run 必须是唯一的首帧：" + r.frames.stream().map(SseEvent::getEvent).toList());
    }

    @Test
    @DisplayName("事件名全部小写，且顺序正确（v1 的 TOOL_EXECUTION 全大写已统一）")
    void eventNamesAreLowerCaseAndOrdered() {
        Recorder r = recorder();
        r.writeThinking(new PartialThinking("想一下"));
        r.writeContent("你好");
        r.writeToolRequest("id-1", "get_date", "{}");
        r.writeToolResult(ToolExecutionRequest.builder().id("id-1").name("get_date").arguments("{}").build(),
                false, "2026-09-30");
        r.writeArtifact(Map.of("id", 9L, "name", "报告.docx"));
        r.finish();

        List<String> names = r.frames.stream().map(SseEvent::getEvent).toList();
        assertEquals(List.of("run", "message", "message", "tool_execution",
                "tool_execution_result", "artifact", "finish"), names);

        for (String name : names) {
            assertEquals(name, name.toLowerCase(), "事件名必须全小写：" + name);
        }
    }

    @Test
    @DisplayName("seq 从 1 连续递增，同一 Run 内 runId 不变（可用于判断丢帧）")
    void seqIsMonotonicAndRunIdStable() {
        Recorder r = recorder();
        for (int i = 0; i < 5; i++) {
            r.writeContent("字");
        }
        r.finish();

        assertEquals(List.of("run", "message", "finish"),
                r.frames.stream().map(SseEvent::getEvent).toList(),
                "5 个 token 应被合并成 1 个 message 帧（P2-12）");

        long expected = 1;
        for (SseEvent e : r.frames) {
            assertEquals(expected++, e.getSeq(), "seq 必须连续递增，跳号说明丢帧");
            assertEquals(RUN_ID, e.getRunId(), "同一 Run 内 runId 必须一致");
            assertNotNull(e.getData(), "每帧都要有 data");
        }
    }

    @Test
    @DisplayName("finish 的 data 是 {status: DONE}（v1 是裸字符串 \"DONE\"）")
    void finishCarriesStatus() {
        Recorder r = recorder();
        r.writeContent("你好");
        r.finish();

        SseEvent finish = r.frames.get(r.frames.size() - 1);
        assertEquals(SseEventType.FINISH.getValue(), finish.getEvent());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) finish.getData();
        assertEquals("DONE", data.get("status"));
    }

    @Test
    @DisplayName("不再下发已废弃的 session_id 事件")
    void noDeprecatedSessionIdEvent() {
        Recorder r = recorder(true, mock(ChatHistoryListService.class));
        r.writeContent("你好");
        r.finish();

        assertTrue(r.frames.stream().noneMatch(e -> "session_id".equals(e.getEvent())),
                "session_id 已并入首帧 run 事件");
    }

    @Test
    @DisplayName("error 帧带 message/hint，runId 提升到信封层（v1 放在 data 里）")
    void errorFrameShape() {
        Recorder r = recorder();
        r.onError(new IllegalStateException("模型超时"));

        SseEvent err = r.frames.get(r.frames.size() - 1);
        assertEquals(SseEventType.ERROR.getValue(), err.getEvent());
        assertEquals(RUN_ID, err.getRunId());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) err.getData();
        assertEquals(MessageType.ERROR.getValue(), data.get("type"));
        assertEquals("模型超时", data.get("message"));
        assertTrue(String.valueOf(data.get("hint")).contains("runId"));
    }

    @Test
    @DisplayName("报错前先把已生成的正文发出去（尾部内容不能被吞）")
    void flushesBeforeError() {
        Recorder r = recorder();
        r.writeContent("已生成的正文");
        r.onError(new IllegalStateException("boom"));

        List<String> names = r.frames.stream().map(SseEvent::getEvent).toList();
        assertEquals(List.of("run", "message", "error"), names);
    }

    @Test
    @DisplayName("工具事件前先冲刷正文（正文不能插到工具卡片后面）")
    void flushesBeforeToolEvent() {
        Recorder r = recorder();
        r.writeContent("先说一句");
        r.writeToolResult(ToolExecutionRequest.builder().id("id-1").name("get_date").arguments("{}").build(),
                false, "2026-09-30");

        assertEquals(List.of("run", "message", "tool_execution_result"),
                r.frames.stream().map(SseEvent::getEvent).toList());
    }

    @Test
    @DisplayName("新会话时在 finish 里生成标题（sessionId 已在首帧给过，这里不重复下发）")
    void createsTitleForNewSession() {
        ChatHistoryListService history = mock(ChatHistoryListService.class);
        Recorder r = recorder(true, history);
        r.writeContent("正文");
        r.finish();

        verify(history).createTitle(eq("sess-1"), eq("你好"), eq("正文"), any());
    }
}
