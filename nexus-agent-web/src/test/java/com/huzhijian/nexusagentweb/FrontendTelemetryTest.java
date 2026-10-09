package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.Result;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-06 fronted 提的两个建议的护栏。
 *
 * <p>① <b>SSE 收尾事件带 ttfbMs</b>：前端自己能测「点发送 → 第一个字出现」，
 * 但那个数里混了网络往返与反代缓冲，与服务端 {@code CHAT_TTFB} 对不上表。
 * 出现「前端显示 300ms、服务端日志 1800ms」时，两边都以为对方错了。
 * <p>
 * ② <b>上传被拒时 data 带配额快照</b>：只有一句文案的话，前端只能弹个 toast 然后
 * 让用户「再试一次」—— 可他根本不知道自己离上限还有多远。
 */
@DisplayName("服务端口径的延迟与配额数据要下发给前端（fronted 建议①②）")
class FrontendTelemetryTest {

    private static final String RUN_ID = "run-abc";
    private static final String SESSION_ID = "sess-1";

    @Test
    @DisplayName("finish 事件带 ttfbMs（与 CHAT_TTFB 同一口径）")
    void finishCarriesTtfb() {
        Recorder r = new Recorder();
        r.markTtfb(1820);
        r.writeContent("正文");
        r.finish();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "finish");
        // ⚠️ 2026-10-08：断言的是 Integer 而不是 Long —— 必须是 int，否则会被全局
        // Long→String 序列化成 "1820"（frontend 实测抓到过）。形态由 NumberFieldSerializationTest 守。
        assertEquals(1820, data.get("ttfbMs"),
                "前端要能直接显示服务端口径的首字延迟，不用再拿浏览器本地测量对表");
        assertEquals("DONE", data.get("status"), "原有字段不能少");
    }

    @Test
    @DisplayName("finish 事件带 preflightMs（后端自己的前置耗时）—— 有了它才能区分「谁慢」")
    void finishCarriesPreflight() {
        Recorder r = new Recorder();
        r.markPreflightMs(24);
        r.markTtfb(25623);
        r.writeContent("正文");
        r.finish();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "finish");
//        同样必须断言 Integer：Long 会被全局配置序列化成字符串
        assertEquals(24, data.get("preflightMs"),
                "后端前置耗时必须下发 —— 它是「这次慢是谁的锅」的唯一凭据（日志里才有 CHAT_PREFLIGHT）");

//        归因口径：两者相减就是纯模型侧等待（2026-10-08 实测一轮：25.6s 首字里后端只占 24ms）
        int modelWait = (Integer) data.get("ttfbMs") - (Integer) data.get("preflightMs");
        assertEquals(25599, modelWait, "ttfbMs − preflightMs 应当等于纯模型侧等待");
    }

    @Test
    @DisplayName("🔴 firstTokenMs 把「供应商排队」和「模型思考」分开（首字 58s 悬案的最后一块）")
    void firstTokenSplitsQueueingFromThinking() {
//        场景 A：请求发出去后一个字都不来（供应商排队 12s），接着很快出正文
        Recorder queued = new Recorder();
        queued.markPreflightMs(24);
        queued.markFirstTokenMs(12024);
        queued.markTtfb(12600);
        queued.writeContent("正文");
        queued.finish();
        @SuppressWarnings("unchecked")
        Map<String, Object> a = lastData(queued, "finish");
        assertEquals(24, a.get("preflightMs"));
        assertEquals(12024, a.get("firstTokenMs"));
        assertEquals(12000, (Integer) a.get("firstTokenMs") - (Integer) a.get("preflightMs"),
                "这段是供应商排队：连接建了但一个字都不来");
        assertEquals(576, (Integer) a.get("ttfbMs") - (Integer) a.get("firstTokenMs"),
                "这段是模型思考/生成");

//        场景 B：字很快就来了，但思考了很久才出正文 —— 同样是"首字慢"，归因完全不同
        Recorder thinking = new Recorder();
        thinking.markPreflightMs(24);
        thinking.markFirstTokenMs(1800);
        thinking.markTtfb(51000);
        thinking.writeContent("正文");
        thinking.finish();
        @SuppressWarnings("unchecked")
        Map<String, Object> b = lastData(thinking, "finish");
        assertEquals(1776, (Integer) b.get("firstTokenMs") - (Integer) b.get("preflightMs"),
                "供应商没排队 —— 这个数很小");
        assertEquals(49200, (Integer) b.get("ttfbMs") - (Integer) b.get("firstTokenMs"),
                "全花在模型思考上 —— 用户能看到「思考中」在爬，只能换模型或等");
    }

    @Test
    @DisplayName("没登记前置耗时时不出现 preflightMs（与 ttfbMs 同一套「缺失而非 null」约定）")
    void preflightAbsentWhenUnset() {
        Recorder r = new Recorder();
        r.writeContent("正文");
        r.finish();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "finish");
        assertFalse(data.containsKey("preflightMs"), () -> "实际：" + data);
//        反向验证：登记了就一定出现，否则上面那条是恒真断言
        Recorder r2 = new Recorder();
        r2.markPreflightMs(7);
        r2.writeContent("正文");
        r2.finish();
        @SuppressWarnings("unchecked")
        Map<String, Object> data2 = lastData(r2, "finish");
        assertTrue(data2.containsKey("preflightMs"), () -> "实际：" + data2);
    }

    @Test
    @DisplayName("stopped 事件也带 ttfbMs（被叫停时用户同样想知道等了多久）")
    void stoppedCarriesTtfb() {
        Recorder r = new Recorder();
        r.markTtfb(950);
        r.writeContent("答了一半");
        r.writeStopped(4);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "stopped");
        assertEquals(950, data.get("ttfbMs"));
        assertEquals(4, data.get("partial"), "原有字段不能少");
    }

    @Test
    @DisplayName("error 事件也带 ttfbMs（出错前的等待同样是延迟数据）")
    void errorCarriesTtfb() {
        Recorder r = new Recorder();
        r.markTtfb(30000);
        r.writeContent("开始说了");
        r.onError(new IllegalStateException("boom"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "error");
        assertEquals(30000, data.get("ttfbMs"));
        assertEquals("ERROR", data.get("type"), "原有字段不能少");
    }

    @Test
    @DisplayName("没测到首字时字段直接不出现，而不是塞个 null（前端少一个 falsy 判断）")
    void ttfbFieldAbsentWhenUnknown() {
        Recorder r = new Recorder();
        r.writeContent("正文");
        r.finish();

        @SuppressWarnings("unchecked")
        Map<String, Object> data = lastData(r, "finish");
        assertFalse(data.containsKey("ttfbMs"),
                "契约写明「可能不存在」比「存在但为 null」对前端更省事");
    }

    @Test
    @DisplayName("Result.error(msg, data) 带上配额快照；data 为 null 时与老行为一致")
    void errorResultCarriesQuotaSnapshot() {
        Map<String, Object> quota = new LinkedHashMap<>();
        quota.put("fileQuota", 100L);
        quota.put("fileUsed", 100L);
        quota.put("fileRemaining", 0L);
        quota.put("fileUnlimited", false);

        Result withData = Result.error("今日文件与产物数量已达上限（已用 100 / 上限 100），明天 00:00 自动重置。", quota);
        assertEquals(1, withData.getCode());
        assertEquals(quota, withData.getData(), "前端要靠它提前禁用上传、显示剩余数");

        Result legacy = Result.error("还是只有文案");
        assertEquals(1, legacy.getCode());
        assertNull(legacy.getData(), "老调用方不该受影响");
    }

    @Test
    @DisplayName("Result.error 与 Result.ok 的参数顺序相反 —— 钉住，别顺手写反")
    void resultArgumentOrderIsAsymmetric() {
        // ok(data)：数据在前；ok(msg, data)：msg 在前。两者顺序**相反**，写反了类型刚好都是
        // (String, Object) 之外的组合，编译器不会报错，运行期才暴露成 msg 变成对象。
        assertEquals(0, Result.ok("payload").getCode());
        assertEquals("payload", Result.ok("payload").getData());
        assertEquals("hello", Result.ok("hello", "payload").getMsg());
        assertEquals("payload", Result.ok("hello", "payload").getData());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastData(Recorder r, String event) {
        SseEvent last = r.frames.stream()
                .filter(f -> event.equals(f.getEvent()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("没有 " + event + " 事件，实际有："
                        + r.frames.stream().map(SseEvent::getEvent).toList()));
        Object data = last.getData();
        assertTrue(data instanceof Map, event + " 的 data 应该是 Map，实际是 " + data);
        return (Map<String, Object>) data;
    }

    private static class Recorder extends SseResponseConverter {
        final List<SseEvent> frames = new ArrayList<>();

        Recorder() {
            super(new SseEmitter(), false, mock(ChatHistoryListService.class),
                    SESSION_ID, 1L, "你好", RUN_ID, 1, 1L,
                    // 本测试不涉及工具可见性：传 null = 不隐藏任何工具（与改动前行为一致）
                    null);
            start();
        }

        @Override
        protected void dispatch(SseEvent event) {
            frames.add(event);
        }
    }

    /** 防止 MessageVO 的新字段被误删（前端已在用 id / supersededBy） */
    @Test
    @DisplayName("MessageVO 仍有 id 与 supersededBy（前端 n/n 切换依赖，删了会静默失效）")
    void messageVoStillCarriesVersionFields() {
        // ⚠️ MessageVO 有 @Builder 就没有无参构造器，只能走 builder
        MessageVO vo = MessageVO.builder().id(123L).supersededBy(456L).build();
        assertEquals(123L, vo.getId());
        assertEquals(456L, vo.getSupersededBy());
    }
}
