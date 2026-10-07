package com.huzhijian.nexusagentweb.converter;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * SSE 层对隐藏工具的过滤（2026-10-07）。
 * <p>
 * <b>钉死的不变式</b>：
 * <ol>
 *   <li>隐藏工具<b>既不发</b> {@code tool_execution} <b>也不发</b> {@code tool_execution_result} ——
 *       只发一半会在前端留下一个永远等不到配对的孤儿卡片；</li>
 *   <li>可见工具的行为<b>完全不变</b>（事件名、载荷形状都不能被这次改动碰坏）；</li>
 *   <li>隐藏工具执行期间用户已累积的正文<b>不许丢</b> —— 屏蔽的是工具，不是正文。</li>
 * </ol>
 *
 * <p><b>为什么这个测试放在 {@code converter} 包</b>：{@code dispatch} 是 protected，
 * 且是整个类<b>唯一的发送出口</b>（注释里写明它是为单测留的）。放到同包才能覆写它、
 * 直接断言事件名与载荷，而不是去反射 {@code SseEmitter} 内部那一套
 * {@code Set<DataWithMediaType>}（外面几乎读不回来）。
 */
@DisplayName("SSE —— 隐藏工具不下发事件，可见工具行为不变")
class SseToolVisibilityTest {

    /** 覆写唯一出口，把发出的事件原样录下来 */
    private static class RecordingConverter extends SseResponseConverter {
        final List<SseEvent> events = new ArrayList<>();

        RecordingConverter(ToolVisibility visibility) {
            super(mock(SseEmitter.class), false, mock(ChatHistoryListService.class),
                    "sess-1", 1L, "写个脚本", "run-1", 200, 60L, visibility);
        }

        @Override
        protected void dispatch(SseEvent event) {
            events.add(event);
        }
    }

    private static RecordingConverter writer() {
        return new RecordingConverter(new ToolVisibility(new AgentProperties()));
    }

    private static List<SseEvent> ofType(RecordingConverter w, String event) {
        return w.events.stream().filter(e -> event.equals(e.getEvent())).toList();
    }

    private static ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    @Test
    @DisplayName("隐藏工具（create_box）：请求与结果事件都不发")
    void hiddenToolEmitsNothing() {
        RecordingConverter w = writer();
        w.start();
        w.writeToolRequest("id-1", "create_box", "{}");
        w.writeToolResult(request("id-1", "create_box"), false, "{\"box_id\":\"b1\"}");

        assertTrue(ofType(w, "tool_execution").isEmpty(), "create_box 不该发 tool_execution");
        assertTrue(ofType(w, "tool_execution_result").isEmpty(), "create_box 不该发 tool_execution_result");
//        但连接与收尾照常：run 帧必须还在（否则前端连 sessionId 都拿不到）
        assertEquals(1, ofType(w, "run").size());
    }

    @Test
    @DisplayName("可见工具（execute_code）：请求与结果事件照发，形状不变")
    void visibleToolStillEmits() {
        RecordingConverter w = writer();
        w.start();
        w.writeToolRequest("id-2", "execute_code", "{\"code\":\"print(1)\"}");
        w.writeToolResult(request("id-2", "execute_code"), false, "1");

        List<SseEvent> requests = ofType(w, "tool_execution");
        List<SseEvent> results = ofType(w, "tool_execution_result");
        assertEquals(1, requests.size());
        assertEquals(1, results.size());
//        载荷形状必须与契约一致：前端是靠 type + toolRequestList / toolResultVO 解析的
        MessageVO reqBody = (MessageVO) requests.get(0).getData();
        assertEquals("TOOL_EXECUTION", reqBody.getType().name());
        assertEquals("execute_code", reqBody.getToolRequestList().get(0).getToolName());
        MessageVO resBody = (MessageVO) results.get(0).getData();
        assertEquals("TOOL_EXECUTION_RESULT", resBody.getType().name());
        assertEquals("execute_code", resBody.getToolResultVO().getToolName());
    }

    @Test
    @DisplayName("混合批次：只发可见工具那一半（并行调用时尤其重要）")
    void mixedBatchFiltersOnlyHidden() {
        RecordingConverter w = writer();
        w.start();
        w.writeToolRequest("id-1", "create_box", "{}");
        w.writeToolRequest("id-2", "execute_code", "{}");
        w.writeToolResult(request("id-1", "create_box"), false, "ok");
        w.writeToolResult(request("id-2", "execute_code"), false, "1");

        List<SseEvent> requests = ofType(w, "tool_execution");
        assertEquals(1, requests.size(), "只应有 execute_code 一个调用事件");
        assertEquals("execute_code",
                ((MessageVO) requests.get(0).getData()).getToolRequestList().get(0).getToolName());
        assertEquals(1, ofType(w, "tool_execution_result").size());
    }

    @Test
    @DisplayName("反向验证：清空隐藏清单后 create_box 重新下发（证明是配置在驱动）")
    void emptyConfigReEmits() {
        AgentProperties props = new AgentProperties();
        props.getTools().getHiddenTools().clear();
        RecordingConverter w = new RecordingConverter(new ToolVisibility(props));
        w.start();
        w.writeToolRequest("id-1", "create_box", "{}");

        assertFalse(ofType(w, "tool_execution").isEmpty(),
                "配置已清空，create_box 却仍被过滤 —— 说明过滤被写死在代码里了");
    }

    @Test
    @DisplayName("流式帧必须带 index：它是同批调用里唯一且首帧就有的序号（id 可能缺失/重复）")
    void streamFrameCarriesIndex() {
        RecordingConverter w = writer();
        w.start();
//        index 由模型流式帧给出；同一批并行调用里 0/1/2 … 唯一
        w.writeToolRequestWithStream(
                dev.langchain4j.model.chat.response.PartialToolCall.builder()
                        .index(1).id(null).name("execute_code").partialArguments("{\"co").build(),
                null);

        List<SseEvent> requests = ofType(w, "tool_execution");
        assertEquals(1, requests.size());
        MessageVO.ToolRequestVO vo = ((MessageVO) requests.get(0).getData()).getToolRequestList().get(0);
        assertEquals(1, vo.getIndex(), "index 必须下发 —— 前端做列表 key 只能靠它");
//        同一帧里 id 为 null 是允许的（供应商常常不回传），契约里已写明
        assertEquals(null, vo.getId());
    }

    @Test
    @DisplayName("屏蔽工具不能牵连正文：隐藏工具前后的正文都要完整送达")
    void contentSurvivesHiddenTools() {
        RecordingConverter w = writer();
        w.start();
        w.writeContent("先说一句");
        w.writeToolRequest("id-1", "create_box", "{}");
        w.writeToolResult(request("id-1", "create_box"), false, "ok");
        w.writeContent("再说一句");
        w.finish();

        String delivered = ofType(w, "message").stream()
                .map(e -> (MessageVO) e.getData())
                .map(MessageVO::getContent)
                .reduce("", String::concat);
        assertEquals("先说一句再说一句", delivered,
                "隐藏工具前后的正文被吞了 —— 屏蔽的是工具卡片，不是正文");
    }
}
