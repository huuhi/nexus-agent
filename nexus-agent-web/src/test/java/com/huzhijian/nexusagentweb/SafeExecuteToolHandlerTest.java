package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.handler.SafeExecuteToolHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SafeExecuteToolHandler 的纯单元测试（不依赖 Spring、不访问外部服务）。
 */
class SafeExecuteToolHandlerTest {

    private final SafeExecuteToolHandler handler = new SafeExecuteToolHandler();

    /**
     * Supplier 不允许抛受检异常，但这里必须真的抛 ConnectException/TimeoutException
     * 才能验证错误码分类，所以用这个标准技巧「偷偷」抛出。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    @DisplayName("异常没有 message 时不能自己抛 NPE（这是修复前的真实缺陷）")
    void exceptionWithNullMessageDoesNotThrow() {
        // 原实现用 Map.of("error", e.getMessage(), ...)，而 Map.of 不接受 null，
        // 于是「工具失败」会升级成「错误处理自身抛 NPE」
        Map<String, Object> result = handler.mapTool("t", () -> {
            throw new IllegalStateException();
        });

        assertEquals(Boolean.FALSE, result.get("success"));
        assertNotNull(result.get("message"), "message 必须兜底，不能为 null");
        assertFalse(String.valueOf(result.get("message")).isBlank());
        assertNotNull(result.get("errorCode"));
    }

    @Test
    @DisplayName("连接被拒 → SERVICE_UNREACHABLE，并给出不要重试的提示")
    void connectionRefusedMapsToServiceUnreachable() {
        Map<String, Object> result = handler.mapTool("box", () -> {
            sneakyThrow(new ConnectException("Connection refused"));
            return null;
        });

        assertEquals(SafeExecuteToolHandler.CODE_SERVICE_UNREACHABLE, result.get("errorCode"));
        assertTrue(String.valueOf(result.get("hint")).contains("不要重复重试"));
    }

    @Test
    @DisplayName("根因被包在外层异常里时，仍能沿 cause 链识别出错误码")
    void rootCauseIsFoundThroughCauseChain() {
        // 复现 WebClient 的包装方式：外层是业务异常，真正的 ConnectException 在 cause
        Map<String, Object> result = handler.mapTool("box", () -> {
            sneakyThrow(new IllegalStateException("调用沙盒失败", new ConnectException("Connection refused")));
            return null;
        });

        assertEquals(SafeExecuteToolHandler.CODE_SERVICE_UNREACHABLE, result.get("errorCode"));
    }

    @Test
    @DisplayName("超时 → TIMEOUT")
    void timeoutMapsToTimeout() {
        Map<String, Object> result = handler.mapTool("box", () -> {
            sneakyThrow(new TimeoutException("read timed out"));
            return null;
        });
        assertEquals(SafeExecuteToolHandler.CODE_TIMEOUT, result.get("errorCode"));
    }

    @Test
    @DisplayName("外部服务返回 null → EMPTY_RESPONSE，而不是把 null 交给模型")
    void nullResultMapsToEmptyResponse() {
        Map<String, Object> result = handler.mapTool("box", () -> null);

        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals(SafeExecuteToolHandler.CODE_EMPTY_RESPONSE, result.get("errorCode"));
    }

    @Test
    @DisplayName("超长异常信息会被截断，避免把大段文本塞进模型上下文")
    void longMessageIsTruncated() {
        String huge = "x".repeat(5000);
        Map<String, Object> result = handler.mapTool("box", () -> {
            throw new IllegalStateException(huge);
        });

        String message = String.valueOf(result.get("message"));
        assertTrue(message.length() <= 310, "截断后长度应接近 300，实际 " + message.length());
        assertTrue(message.endsWith("..."));
    }

    @Test
    @DisplayName("listTool 失败时返回单元素列表，形状与成功时一致")
    void listToolWrapsFailureAsSingleElementList() {
        List<Map<String, Object>> result = handler.listTool("list_dir", () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(1, result.size());
        assertEquals(Boolean.FALSE, result.get(0).get("success"));
    }

    @Test
    @DisplayName("stringTool 失败时返回可解析的紧凑 JSON")
    void stringToolReturnsJsonOnFailure() throws Exception {
        String result = handler.stringTool("some_tool", () -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(result.startsWith("{") && result.endsWith("}"));
        // 借用 Jackson（已在依赖中）验证它是合法 JSON
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
        assertEquals("UNKNOWN", node.get("errorCode").asText());
    }

    @Test
    @DisplayName("成功时原样返回，不做包装")
    void successPassesThrough() {
        Map<String, Object> ok = Map.of("success", true, "stdout", "hi");
        assertEquals(ok, handler.mapTool("box", () -> ok));
    }
}
