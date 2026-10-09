package com.huzhijian.nexusagentweb.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huzhijian.nexusagentweb.config.JacksonConfig;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.tools.ToolSourceStore;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import com.huzhijian.nexusagentweb.tools.WebSearchTool;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「搜索来源」这条链路的端到端测试（2026-10-08 新增）。
 * <p>
 * 目标：前端要做那种 Perplexity 式的 UI（顶部「已搜索 N 个来源」+ 来源卡片 + 正文 {@code [1]} 角标），
 * 后端必须把<b>结构化来源数组</b>送过去。这条链跨越两个组件：
 * <pre>
 * WebSearchTool → ToolSourceStore（带外暂存）→ SseResponseConverter → SSE tool_execution_result.sources
 * </pre>
 * 任何一个环节掉了链子，前端拿到的都是空 —— 而且<b>不会报错</b>，只是"来源卡片不出现"。
 * 这种静默失效正是要在这里钉死的。
 * <p>
 * <b>为什么放在 {@code converter} 包</b>：{@code dispatch} 是 protected 且是唯一发送出口，
 * 覆写它才能直接读到事件载荷。
 */
@DisplayName("搜索来源下发 —— 工具 → ToolSourceStore → SSE")
class SearchSourceDeliveryTest {

    private static final String SESSION = "sess-1";

    /**
     * 工具侧产出的来源结构（此处手工镜像一份，避免跨包访问包可见方法）。
     * <b>真实形状的断言在同包的 {@code WebSearchToolTest} 里</b> ——
     * 本类只负责「这样一份结构能不能原样送到前端」。
     */
    private static List<Map<String, Object>> fixtureSources() {
        Map<String, Object> first = new java.util.LinkedHashMap<>();
        first.put("index", 1);
        first.put("title", "2026年高考报名人数");
        first.put("url", "https://example.com/a");
        first.put("snippet", "共 1300 万人，创历史新高。");
        Map<String, Object> second = new java.util.LinkedHashMap<>();
        second.put("index", 2);
        second.put("title", "第二条标题");
        second.put("url", "https://example.com/b");
        second.put("snippet", "另一段摘要。");
        return List.of(first, second);
    }
    /** 覆写唯一出口，把发出的事件原样录下来 */
    private static class RecordingConverter extends SseResponseConverter {
        final List<SseEvent> events = new ArrayList<>();

        RecordingConverter(ToolSourceStore store) {
            super(mock(SseEmitter.class), false, mock(ChatHistoryListService.class),
                    SESSION, 1L, "今天的热点", "run-1", 200, 60L,
                    new ToolVisibility(new com.huzhijian.nexusagentweb.properties.AgentProperties()), store);
        }

        @Override
        protected void dispatch(SseEvent event) {
            events.add(event);
        }
    }

    private static ToolExecutionRequest searchRequest() {
        return ToolExecutionRequest.builder()
                .id("call-1").name("web_search").arguments("{\"query\":\"高考人数\"}").build();
    }

    private static List<SseEvent> toolResultEvents(RecordingConverter w) {
        return w.events.stream().filter(e -> "tool_execution_result".equals(e.getEvent())).toList();
    }

    // ==================== SSE 侧：真的发到前端了吗 ====================

    @Test
    @DisplayName("🔴 tool_execution_result 事件里带上 sources，前端才有得渲染")
    void sourcesAreDeliveredInToolResultEvent() {
        ToolSourceStore store = new ToolSourceStore();
        store.record(SESSION, "web_search", fixtureSources());

        RecordingConverter w = new RecordingConverter(store);
        w.writeToolResult(searchRequest(), false, "1. 2026年高考报名人数\n   …");

        List<SseEvent> events = toolResultEvents(w);
        assertEquals(1, events.size(), "没有发出 tool_execution_result 事件");

        MessageVO.ToolResultVO vo = payloadOf(events.get(0));
        assertEquals(2, vo.getSources().size(), "事件里没有带 sources —— 前端拿不到来源卡片");
        assertEquals("https://example.com/a", vo.getSources().get(0).get("url"));
    }

    @Test
    @DisplayName("取一次即消费：同一个来源不会被重复下发到两次工具和事件上")
    void takenSourcesAreNotReplayed() {
        ToolSourceStore store = new ToolSourceStore();
        store.record(SESSION, "web_search", fixtureSources());

        RecordingConverter w = new RecordingConverter(store);
        w.writeToolResult(searchRequest(), false, "第一次");
        w.writeToolResult(ToolExecutionRequest.builder()
                .id("call-2").name("web_search").arguments("{}").build(), false, "第二次");

        List<SseEvent> events = toolResultEvents(w);
        assertEquals(2, events.size());
        assertNull(((MessageVO.ToolResultVO) payloadOf(events.get(1))).getSources(),
                "第二次结果不应再挂同一批来源（那是另一次搜索还没发生）");
    }

    @Test
    @DisplayName("没有来源的工具（如 web_extract）不挂 sources 字段，而不是挂一个 null")
    void toolsWithoutSourcesStayClean() {
        RecordingConverter w = new RecordingConverter(new ToolSourceStore());
        w.writeToolResult(ToolExecutionRequest.builder()
                .id("call-3").name("web_extract").arguments("{}").build(), false, "正文…");

        String json = serialize(payloadOf(toolResultEvents(w).get(0)));
        assertFalse(json.contains("sources"),
                "无来源的工具结果不该出现 sources 字段（连 null 都不要）：" + json);
    }

    @Test
    @DisplayName("失败的结果不挂来源，且不会把记录吃掉（成功的结果还要用）")
    void failedResultKeepsSourcesForLater() {
        ToolSourceStore store = new ToolSourceStore();
        store.record(SESSION, "web_search", fixtureSources());

        RecordingConverter w = new RecordingConverter(store);
        w.writeToolResult(searchRequest(), true, "error:搜索失败");

        assertNull(payloadOf(toolResultEvents(w).get(0)).getSources());
        assertEquals(1, store.pendingCount(SESSION, "web_search"),
                "失败时不应该消费掉那条来源记录");
    }

    // ==================== 3. 序列化形态：前端收到的实际 JSON ====================

    @Test
    @DisplayName("🔴 序列化后 index 必须是没有引号的数字（重复 ttfbMs 那个坑）")
    void indexSerializesAsNumberNotString() {
        MessageVO vo = MessageVO.builder()
                .toolResultVO(MessageVO.ToolResultVO.builder()
                        .id("call-1").toolName("web_search").result("…").isError(false)
                        .sources(fixtureSources())
                        .build())
                .build();

        String json = serialize(vo);
        assertTrue(json.contains("\"index\":1"), () -> "index 必须是数字，实际：" + json);
        assertFalse(json.contains("\"index\":\"1\""), () -> "index 变成字符串了：" + json);
    }

    // ==================== 内部工具 ====================

    private static MessageVO.ToolResultVO payloadOf(SseEvent event) {
        Object data = event.getData();
        assertTrue(data instanceof MessageVO,
                () -> "tool_execution_result 的载荷应该是 MessageVO，实际是：" + data);
        return ((MessageVO) data).getToolResultVO();
    }

    /** 与应用运行时同源的 ObjectMapper（沿用 JacksonConfig 的 customizer） */
    private static ObjectMapper contractMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    private static String serialize(Object obj) {
        try {
            return contractMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new AssertionError("序列化失败", e);
        }
    }
}
