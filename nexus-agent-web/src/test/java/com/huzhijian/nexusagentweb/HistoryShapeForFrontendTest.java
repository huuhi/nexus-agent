package com.huzhijian.nexusagentweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.ChatMemoryServiceImpl;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 历史接口的**端到端形状**（2026-10-07，回应 frontend 的崩溃排查）。
 * <p>
 * frontend 报「渲染历史消息时 vue-router 抛
 * {@code TypeError: Cannot set properties of null (setting '__vnode')}」，怀疑是后端
 * 「工具相关返回」改坏了。这个测试就是用来<b>定性</b>的：
 * <ol>
 *   <li>后端返回的 list 里<b>绝不会有 null 元素</b> —— {@code toMessageVO} 返回 null 的行
 *       在 {@code getHistoryBySessionId} 里被 {@code continue} 跳过了，不是塞进列表；</li>
 *   <li>被隐藏的工具会<b>整行消失</b>（不是变成 null、也不是变成空壳）；</li>
 *   <li>{@code id} 的形态<b>没有任何变化</b>（消息 id 是 19 位雪花 ID 的字符串形态，
 *       工具 id 是模型给的字符串，两者都不受本次改动影响）。</li>
 * </ol>
 * 顺带把真实的历史 JSON 打到标准输出，用于给前端做样例（见 {@link #printSample()}）。
 */
@DisplayName("历史接口端到端形状 —— 无 null 元素、隐藏工具整行消失、id 形态不变")
class HistoryShapeForFrontendTest {

    private static ChatHistory row(long id, String type, dev.langchain4j.data.message.ChatMessage msg) {
        return ChatHistory.builder()
                .id(id)
                .userId(1L)
                .type(type)
                .content(ChatMessageSerializer.messageToJson(msg))
                .sessionId("sess-1")
                .runId("run-1")
                .createAt(new Date())
                .build();
    }

    private static ToolExecutionRequest req(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{\"code\":\"print(1)\"}").build();
    }

    /** 一轮带并行工具调用的对话：建沙盒（隐藏）+ 执行代码（可见） */
    private static List<ChatHistory> rows() {
        List<ChatHistory> rows = new ArrayList<>();
        rows.add(row(1001L, "USER", UserMessage.from("帮我算一下 1+1")));
        rows.add(row(1002L, "AI", AiMessage.from(List.of(req("call_1", "create_box"), req("call_2", "execute_code")))));
        rows.add(row(1003L, "TOOL_EXECUTION_RESULT", ToolExecutionResultMessage.from("call_1", "create_box", "{\"box_id\":\"b-1\"}")));
        rows.add(row(1004L, "TOOL_EXECUTION_RESULT", ToolExecutionResultMessage.from("call_2", "execute_code", "2")));
        rows.add(row(1005L, "AI", AiMessage.from("答案是 2")));
        return rows;
    }

    private static List<MessageVO> history(ToolVisibility visibility) {
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        when(mapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(rows());
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "toolVisibility", visibility);
        try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
            holder.when(UserContextHolder::getUserId).thenReturn(1L);
            return service.getHistoryBySessionId("sess-1");
        }
    }

    @Test
    @DisplayName("🔴 列表里不含 null 元素 —— 前端不会收到 [null, ...]")
    void noNullElements() {
        List<MessageVO> list = history(new ToolVisibility(new AgentProperties()));
        assertTrue(list.stream().noneMatch(Objects::isNull),
                "历史里出现了 null 元素 —— 前端 v-for 渲染 null 正是 'Cannot set properties of null' 的来源");
        for (MessageVO vo : list) {
            assertNotNull(vo.getId(), "每条历史都必须带 id（前端用它做 key）");
        }
    }

    @Test
    @DisplayName("隐藏工具是「整行消失」而不是「变成空壳」：5 行进、4 行出")
    void hiddenToolRowsDisappear() {
        List<MessageVO> list = history(new ToolVisibility(new AgentProperties()));
//        USER / AI(仅 execute_code 可见) / result(execute_code) / AI(最终回答)
        assertEquals(4, list.size(), "create_box 的结果行应整行消失，其余保留");
        boolean anyCreateBox = list.stream().anyMatch(vo ->
                (vo.getToolResultVO() != null && "create_box".equals(vo.getToolResultVO().getToolName()))
                        || (vo.getToolRequestList() != null && vo.getToolRequestList().stream()
                        .anyMatch(r -> "create_box".equals(r.getToolName()))));
        assertTrue(!anyCreateBox, "历史里不应出现任何 create_box");
    }

    @Test
    @DisplayName("id 形态不变：消息 id 是雪花 ID（Long→String），工具 id 是模型给的字符串")
    void idShapeUnchanged() {
        List<MessageVO> list = history(new ToolVisibility(new AgentProperties()));
//        消息 id：Long 类型（序列化层统一转字符串，见 JacksonConfig）
        assertEquals(1001L, list.get(0).getId());
//        工具 id：模型给的原始字符串，原样透传
        MessageVO ai = list.stream().filter(v -> v.getToolRequestList() != null
                && !v.getToolRequestList().isEmpty()).findFirst().orElseThrow();
        assertEquals("call_2", ai.getToolRequestList().get(0).getId());
        assertEquals("execute_code", ai.getToolRequestList().get(0).getToolName());
        MessageVO result = list.stream().filter(v -> v.getToolResultVO() != null).findFirst().orElseThrow();
        assertEquals("call_2", result.getToolResultVO().getId());
    }

    @Test
    @DisplayName("🔴 工具 id 缺失时后端合成唯一 id —— 前端拿它做 key 不会撞车")
    void missingToolIdsAreBackfilled() {
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        List<ChatHistory> rows = new ArrayList<>();
//        两个工具调用的 id 都是 null（模拟不回传 tool_call id 的供应商）
        rows.add(row(2001L, "AI", AiMessage.from(List.of(
                ToolExecutionRequest.builder().name("execute_code").arguments("{}").build(),
                ToolExecutionRequest.builder().name("list_dir").arguments("{}").build()))));
        rows.add(row(2002L, "TOOL_EXECUTION_RESULT",
                ToolExecutionResultMessage.from(null, "execute_code", "2")));
        when(mapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(rows);

        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "toolVisibility", new ToolVisibility(new AgentProperties()));
        List<MessageVO> list;
        try (MockedStatic<UserContextHolder> holder = mockStatic(UserContextHolder.class)) {
            holder.when(UserContextHolder::getUserId).thenReturn(1L);
            list = service.getHistoryBySessionId("sess-1");
        }

        List<String> ids = new ArrayList<>();
        for (MessageVO vo : list) {
            if (vo.getToolRequestList() != null) {
                vo.getToolRequestList().forEach(r -> ids.add(r.getId()));
            }
            if (vo.getToolResultVO() != null) {
                ids.add(vo.getToolResultVO().getId());
            }
        }
        assertEquals(3, ids.size());
        assertTrue(ids.stream().noneMatch(s -> s == null || s.isBlank()),
                "历史里出现了空 id —— 前端拿它做 v-for key 会撞车（Cannot set properties of null）");
        assertEquals(ids.size(), new java.util.HashSet<>(ids).size(),
                "合成 id 必须互不相同，否则 key 照样撞车：" + ids);
    }

    @Test
    @DisplayName("反向验证：清空隐藏清单后 5 行全返回（证明是配置在驱动行数变化）")
    void emptyConfigKeepsAllRows() {
        AgentProperties props = new AgentProperties();
        props.getTools().getHiddenTools().clear();
        assertEquals(5, history(new ToolVisibility(props)).size(),
                "配置清空后行数应恢复 —— 说明「行数变少」确实来自隐藏清单");
    }

    /**
     * 打印真实的历史 JSON 样例（与线上序列化口径一致：Long → 字符串）。
     * 不是断言，是给前端的对照物 —— 人工跑一次即可看到实际形状。
     */
    @Test
    @DisplayName("打印改后的历史 JSON 样例（给前端做对照）")
    void printSample() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(module);

        List<MessageVO> list = history(new ToolVisibility(new AgentProperties()));
        System.out.println("=====HISTORY_SAMPLE_BEGIN=====");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(list));
        System.out.println("=====HISTORY_SAMPLE_END=====");
    }
}
