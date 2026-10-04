package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.handler.GlobalExceptionHandler;
import com.huzhijian.nexusagentweb.vo.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「SSE 流里抛异常」这条路径的处理方式 —— 2026-10-04 线上刷了一屏
 * {@code HttpMessageNotWritableException: No converter for [...] with preset Content-Type
 * 'text/event-stream'}，根因是响应头已经切成 event-stream，却还想写 JSON 信封。
 * <p>
 * 这类问题用 mock 掉 {@code SseEmitter} 的单测是测不到的：
 * 它取决于 **Servlet 容器的 Content-Type 与 HttpMessageConverter 的交互**。
 * 这里用 {@link MockHttpServletResponse} 保留真实的 Content-Type 语义，
 * 只把容器本身换成内存实现，属于"比单测高一层、比起服务器低一层"的验证。
 */
@DisplayName("SSE 流里的异常：写成 error 帧，不再试图写 JSON 信封")
class SseErrorFrameTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("已在 SSE 流中：直接补一条 event: error 帧，不返回 ResponseEntity")
    void writesErrorFrameWhenStreamingHasStarted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
//        SseEmitter 一旦开始发送，容器就会把 Content-Type 切成这个
        response.setContentType("text/event-stream");

        ResponseEntity<Result> result =
                handler.handleUnexpected(new IllegalStateException("上游返回 401"), request, response);

        assertNull(result, "响应已经开始了，不该再让 Spring 去写 ResponseEntity");
        String body = response.getContentAsString();
        assertTrue(body.startsWith("event: error"), "应是 SSE 的 error 帧，实际：" + body);
        assertTrue(body.contains("上游返回 401"), "错误信息要传给前端，实际：" + body);
        assertTrue(body.endsWith("\n\n"), "SSE 帧必须以空行结束，否则浏览器 EventSource 收不到：" + body);
    }

    @Test
    @DisplayName("普通请求：仍然返回 500 + Result 信封（行为不变）")
    void keepsJsonEnvelopeForNormalRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Result> result =
                handler.handleUnexpected(new IllegalStateException("boom"), request, response);

        assertNotNull(result);
        assertEquals(500, result.getStatusCode().value());
        assertNotNull(result.getBody());
    }

    @Test
    @DisplayName("错误原因为 null 时不 NPE，退化成异常类名")
    void nullMessageDoesNotThrow() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");

        assertNull(handler.handleUnexpected(new IllegalStateException(), request, response));
        assertTrue(response.getContentAsString().contains("IllegalStateException"));
    }
}
