package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.ContextUsage;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.em.SseEventType;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「上下文用量」信号的单测（2026-10-06 新增）。
 * <p>
 * <b>为什么要这个信号</b>：前端判断「要不要建议用户开新会话」此前只能靠<b>数消息条数</b>
 * （历史做法：超过 12 条就提示）。条数与真实占用完全不成正比 —— 一轮带工具调用的
 * agent 对话（沙盒输出、检索结果）能顶几十轮纯文本闲聊。
 * 用户反馈的原话：「现在很多模型都是 1M，对话几次就让我 new 窗口，怎么可能那么快」。
 * 有了服务端口径的真实用量，前端才不用猜。
 * <p>
 * 覆盖两条容易做错的地方：
 * <ol>
 *   <li>{@code ratio} 的边界（窗口为 0 不能产出 {@code NaN}，用量超窗口要能如实反映 > 1）；</li>
 *   <li>字段只在<b>登记过</b>快照时才出现 —— 未登记时不该塞一堆 0 误导前端。</li>
 * </ol>
 */
@DisplayName("上下文用量信号 —— 让前端不再靠数消息条数猜窗口")
class ContextUsageSignalTest {

    private static final String RUN_ID = "a1b2c3d4e5f6";

    /** 关掉自动冲刷：只在类型切换 / 强制冲刷时发帧 */
    private static final int NEVER_FLUSH_CHARS = Integer.MAX_VALUE;
    private static final long NEVER_FLUSH_MILLIS = 1_000_000L;

    /** 记录所有发出的帧；不真正写 SseEmitter（与 SseContractTest 同一手法） */
    private static class Recorder extends SseResponseConverter {
        final List<SseEvent> frames = new ArrayList<>();

        Recorder() {
            super(new SseEmitter(), false, mock(ChatHistoryListService.class), "sess-1", 1L,
                    "你好", RUN_ID, NEVER_FLUSH_CHARS, NEVER_FLUSH_MILLIS,
                    // 本测试不涉及工具可见性：传 null = 不隐藏任何工具（与改动前行为一致）
                    null);
        }

        @Override
        protected void dispatch(SseEvent event) {
            frames.add(event);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastData(Recorder r, SseEventType type) {
        for (int i = r.frames.size() - 1; i >= 0; i--) {
            SseEvent e = r.frames.get(i);
            if (type.getValue().equals(e.getEvent())) {
                return (Map<String, Object>) e.getData();
            }
        }
        throw new AssertionError("没有发出 " + type.getValue() + " 事件");
    }

    @Test
    @DisplayName("ratio = 已用 / 窗口；窗口异常为 0 时返回 0，不能产出 NaN")
    void ratioIsSafe() {
        ContextUsage usage = new ContextUsage(300_000);
        usage.recordLoaded(42, 12_480);
        assertEquals(42, usage.loadedMsgs());
        assertEquals(12_480, usage.loadedTokens());
        assertEquals(300_000, usage.window());
        assertEquals(0.0416, usage.ratio(), 1e-9);

//        用量超出窗口：如实反映 > 1（这正是「本轮已开始丢更早历史」的信号）
        usage.recordLoaded(900, 360_000);
        assertTrue(usage.ratio() > 1.0, "加载量本来就允许超出窗口，ratio 必须能 > 1，"
                + "否则前端永远看不到「已经开始丢历史」这件事");

//        窗口为 0（配置异常）：返回 0 而不是 NaN —— NaN 会污染前端的比较运算
        assertEquals(0.0, new ContextUsage(0).ratio(), "窗口为 0 时必须返回 0，NaN 会让前端所有比较失效");
    }

    @Test
    @DisplayName("finish 事件带上四个 context 字段（登记过快照时）")
    void finishCarriesContextFields() {
        Recorder r = new Recorder();
        ContextUsage usage = new ContextUsage(300_000);
        usage.recordLoaded(42, 12_480);
        r.markContextUsage(usage);

        r.start();
        r.finish();

        Map<String, Object> data = lastData(r, SseEventType.FINISH);
        assertEquals(300_000, data.get("contextWindow"));
        assertEquals(12_480, data.get("contextUsed"));
        assertEquals(42, data.get("contextMsgs"));
        assertEquals(0.0416, ((Number) data.get("contextRatio")).doubleValue(), 1e-6);
//        原有字段不能被顶掉
        assertEquals("DONE", data.get("status"));
    }

    @Test
    @DisplayName("没登记快照时不产出 context 字段 —— 缺字段比塞 0 更诚实")
    void noContextFieldsWhenNotRecorded() {
        Recorder r = new Recorder();
        r.start();
        r.finish();

        Map<String, Object> data = lastData(r, SseEventType.FINISH);
        assertFalse(data.containsKey("contextWindow"));
        assertFalse(data.containsKey("contextUsed"));
        assertFalse(data.containsKey("contextMsgs"));
        assertFalse(data.containsKey("contextRatio"));
        assertEquals("DONE", data.get("status"));
    }

    @Test
    @DisplayName("用户停止生成时也能拿到用量 —— 那往往是「觉得太久」的时刻")
    void stoppedCarriesContextFields() {
        Recorder r = new Recorder();
        ContextUsage usage = new ContextUsage(300_000);
        usage.recordLoaded(80, 290_000);
        r.markContextUsage(usage);

        r.start();
        r.writeContent("已经吐出来的部分");
        r.writeStopped(8);

        Map<String, Object> data = lastData(r, SseEventType.STOPPED);
        assertEquals(290_000, data.get("contextUsed"));
        assertTrue(((Number) data.get("contextRatio")).doubleValue() > 0.9,
                "快撑满窗口时停止，前端应当能从 ratio 看出来");
    }
}
