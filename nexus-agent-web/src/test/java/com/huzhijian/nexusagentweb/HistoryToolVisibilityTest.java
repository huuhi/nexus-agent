package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.ChatMemoryServiceImpl;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史接口（{@code GET /api/history/{sessionId}}）对隐藏工具的过滤（2026-10-07）。
 * <p>
 * <b>为什么必须有这一层</b>：只在 SSE 里屏蔽是不够的 —— 用户刷新页面，
 * 历史从 {@code chat_memory} 读回来，藏起来的工具卡片会原样冒出来，
 * 表现就是「当时看不见、刷新后又有了」，比不屏蔽更让人困惑。
 * <p>
 * <b>但只过滤展示</b>：{@code chat_memory} 里的原始消息<b>一个字都不动</b>。
 * 模型上下文需要完整的 {@code tool_calls} ↔ {@code tool_result} 配对，
 * 从记忆里删一半，下一轮请求会被供应商直接拒（OpenAI 兼容协议的硬约束）。
 * 所以这里测的是「转成前端 VO 时剔掉」，不是「存的时候不存」。
 */
@DisplayName("历史接口 —— 隐藏工具不出现在返回里，且不牵连同批可见内容")
class HistoryToolVisibilityTest {

    private static ChatMemoryServiceImpl service(ToolVisibility visibility) {
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "toolVisibility", visibility);
        return service;
    }

    private static ChatMemoryServiceImpl service() {
        return service(new ToolVisibility(new AgentProperties()));
    }

    /** toMessageVO 是私有方法（只在读历史时内部调用），这里用反射直接测它 */
    private static MessageVO toMessageVO(ChatMemoryServiceImpl service, dev.langchain4j.data.message.ChatMessage msg) {
        return ReflectionTestUtils.invokeMethod(service, "toMessageVO", msg);
    }

    private static ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    @Test
    @DisplayName("整条消息只有隐藏工具（无正文）→ 不返回（否则前端一个空气泡）")
    void pureHiddenToolMessageIsDropped() {
        MessageVO vo = toMessageVO(service(), AiMessage.from(List.of(request("1", "create_box"))));
        assertNull(vo, "只有 create_box 调用的 AiMessage 不该出现在历史里");
    }

    @Test
    @DisplayName("同一条消息里混了隐藏与可见工具 → 只留可见的那个")
    void mixedMessageKeepsVisibleOnly() {
        MessageVO vo = toMessageVO(service(), AiMessage.from(List.of(
                request("1", "create_box"), request("2", "execute_code"))));
        assertNotNull(vo);
        List<String> names = vo.getToolRequestList().stream()
                .map(MessageVO.ToolRequestVO::getToolName).toList();
        assertEquals(List.of("execute_code"), names, "create_box 应从历史里剔除，execute_code 保留");
    }

    @Test
    @DisplayName("带正文的消息即使工具被隐藏也要保留（正文不能跟着丢）")
    void messageWithTextSurvives() {
        MessageVO vo = toMessageVO(service(), AiMessage.from("我先准备一下环境"));
        assertNotNull(vo);
        assertEquals("我先准备一下环境", vo.getContent());
        assertTrue(vo.getToolRequestList() == null || vo.getToolRequestList().isEmpty());
    }

    @Test
    @DisplayName("工具结果行：隐藏的不返回，可见的照常返回")
    void toolResultRowsAreFiltered() {
        assertNull(toMessageVO(service(), ToolExecutionResultMessage.from("1", "create_box", "{\"box_id\":\"b1\"}")),
                "create_box 的结果行不该出现在历史里");
        MessageVO visible = toMessageVO(service(), ToolExecutionResultMessage.from("2", "execute_code", "1"));
        assertNotNull(visible, "execute_code 的结果行必须保留");
        assertEquals("execute_code", visible.getToolResultVO().getToolName());
        assertEquals("1", visible.getToolResultVO().getResult());
    }

    @Test
    @DisplayName("反向验证：清空隐藏清单后历史里不再剔任何东西")
    void emptyConfigKeepsEverything() {
        AgentProperties props = new AgentProperties();
        props.getTools().getHiddenTools().clear();
        ChatMemoryServiceImpl service = service(new ToolVisibility(props));

        assertNotNull(toMessageVO(service, AiMessage.from(List.of(request("1", "create_box")))),
                "配置已清空，create_box 却仍被剔除 —— 说明过滤被写死在代码里了");
        assertNotNull(toMessageVO(service, ToolExecutionResultMessage.from("1", "create_box", "ok")));
    }
}
