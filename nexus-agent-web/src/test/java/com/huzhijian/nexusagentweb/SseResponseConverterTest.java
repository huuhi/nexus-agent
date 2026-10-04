package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SseResponseConverter} 的纯单测 —— 连接断开（超时 / 关网页）语义。
 * <p>
 * 覆盖 2026-10-03 的关键修正：以前 onTimeout → onError → isFinished=true，
 * TokenStream 还在跑但产出全被丢弃（烧钱 + 结果全扔）。
 * 现在 disconnect 只停发送，任务继续跑完，finish() 照常落库标题。
 */
@DisplayName("SseResponseConverter —— 连接断开后任务继续、结果照常落库")
class SseResponseConverterTest {

    private SseEmitter emitter;
    private ChatHistoryListService historyService;

    private SseResponseConverter newWriter() {
        emitter = mock(SseEmitter.class);
        historyService = mock(ChatHistoryListService.class);
        return SseResponseConverter.builder()
                .sseEmitter(emitter)
                .isNewSession(true)
                .chatHistoryListService(historyService)
                .sessionId("sess-1")
                .userId(1L)
                .message("画个图")
                .runId("run-1")
                .build();
    }

    /** 统计已发出的 SSE 帧数 */
    private int sentFrames() throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        return captor.getAllValues().size();
    }

    @Test
    @DisplayName("断开后：不再发送任何帧，但 finish 照常生成标题")
    void disconnectStopsSendingButStillFinalizes() throws Exception {
        SseResponseConverter writer = newWriter();
        writer.start();
        writer.writeContent("一部分正文");
        int framesBefore = sentFrames();
        assertTrue(framesBefore >= 1, "start 至少发出 run 帧");

        writer.disconnect("测试断开");
        writer.writeContent("断开后模型还在吐的内容");
        writer.finish();

//        断开后帧数不再增长
        assertEquals(framesBefore, sentFrames());
//        但标题照常生成 —— 用户刷新页面就能看到完整回复
        verify(historyService).createTitle(eq("sess-1"), eq("画个图"), any(), eq(1L));
    }

    @Test
    @DisplayName("断开是幂等的：多次调用无害")
    void disconnectIsIdempotent() {
        SseResponseConverter writer = newWriter();
        writer.start();
        writer.disconnect("第一次");
        writer.disconnect("第二次");
        writer.finish();
    }

    @Test
    @DisplayName("正常流程：run → message → finish 三类帧都发出，标题生成")
    void normalFlow() throws Exception {
        SseResponseConverter writer = newWriter();
        writer.start();
        writer.writeContent("你好，这是回复。");
        writer.finish();

        assertTrue(sentFrames() >= 3, "至少 run + message + finish 三帧");
        verify(historyService).createTitle(eq("sess-1"), any(), any(), eq(1L));
        verify(emitter).complete();
    }

    @Test
    @DisplayName("finish 是幂等的：重复调用不会重复生成标题")
    void finishIsIdempotent() {
        SseResponseConverter writer = newWriter();
        writer.start();
        writer.finish();
        writer.finish();
        verify(historyService, times(1)).createTitle(any(), any(), any(), any());
    }

    @Test
    @DisplayName("断开后 onError 也不会再推错误帧（连接已没了）")
    void disconnectThenErrorStaysSilent() throws Exception {
        SseResponseConverter writer = newWriter();
        writer.start();
        int before = sentFrames();
        writer.disconnect("测试断开");
        writer.onError(new RuntimeException("模型报错"));
        assertEquals(before, sentFrames());
    }

    /**
     * 2026-10-04 的修复：以前收尾调 {@code emitter.completeWithError(error)}，
     * 容器会对这个异步请求做一次 error dispatch（转发到 /error），而响应的
     * Content-Type 已经是 text/event-stream，没有任何 converter 能写 ——
     * 于是二次抛 HttpMessageNotWritableException，日志刷一屏且前端收不到干净错误。
     * 现在改成先发 error 帧、再 {@code complete()} 正常关流。
     */
    @Test
    @DisplayName("报错收尾：先发 error 帧，再用 complete() 关流（绝不用 completeWithError）")
    void onErrorCompletesNormallyInsteadOfCompleteWithError() throws Exception {
        SseResponseConverter writer = newWriter();
        writer.start();
        int before = sentFrames();

        writer.onError(new IllegalStateException("上游返回 401"));

//        错误帧确实推给了前端（内容有效，不该因为出错就被吞掉）
        assertEquals(before + 1, sentFrames());
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any(Throwable.class));
    }
}
