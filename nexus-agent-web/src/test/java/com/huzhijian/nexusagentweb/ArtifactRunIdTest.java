package com.huzhijian.nexusagentweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.service.impl.ArtifactServiceImpl;
import com.huzhijian.nexusagentweb.service.impl.ChatMemoryServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物归属（方案 B：持久化 runId）的验收测试。
 * <p>
 * 对应《产物归属-后端诉求》§3 的三条验收标准。这里不连数据库，
 * 但断言的是**两条链路能不能对上**：
 * <ol>
 *   <li>多轮会话、每轮各产出一个文件 → 两次运行的 runId 不同，且分别落在各自那一轮；</li>
 *   <li>同一个文件在「历史」与「产物列表」两条链路里对得上（这里锁定 JSON 字段名）；</li>
 *   <li>老数据 runId 缺失 → 接口不报错、字段为 null。</li>
 * </ol>
 * <p>
 * ⚠️ 第 1 条曾经**必然是错的**：历史接口原来是「先把所有行映射成 ChatMessage 列表、
 * 再统一转 VO」，行上的信息（runId）在这一步就丢了，补字段也补不出来。
 * 现在改成逐行处理，下面 {@code 每一轮的 runId 互不混淆} 就是防它回归的。
 */
@DisplayName("产物归属：runId 贯穿落库与历史（方案 B）")
class ArtifactRunIdTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FileMapper fileMapper;
    private ArtifactServiceImpl artifactService;

    private ChatMemoryMapper chatMemoryMapper;
    private ChatMemoryServiceImpl chatMemoryService;

    @BeforeEach
    void setUp() {
        fileMapper = mock(FileMapper.class);
        artifactService = new ArtifactServiceImpl(fileMapper, mock(AliOssUtil.class));

        chatMemoryMapper = mock(ChatMemoryMapper.class);
        chatMemoryService = new ChatMemoryServiceImpl();
        // mapper 是 @Resource 私有字段，测试里只能用反射塞进去
        ReflectionTestUtils.setField(chatMemoryService, "mapper", chatMemoryMapper);

        UserContextHolder.saveId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    // ============ 验收 1：多轮会话，每轮的 runId 不同且各归各轮 ============

    @Test
    @DisplayName("同一轮产出多个文件：共用同一个 runId")
    void sameRunSharesOneRunId() {
        SysFile first = artifactService.save(artifact("data.csv", "https://oss/1.csv"), 1L, "s-1", "run-A");
        SysFile second = artifactService.save(artifact("chart.png", "https://oss/2.png"), 1L, "s-1", "run-A");

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("run-A", first.getRunId());
        assertEquals("run-A", second.getRunId(), "一次运行产出的多个文件必须共用同一个 runId");
    }

    @Test
    @DisplayName("不同轮产出的文件：runId 不同（前端靠它区分是哪一轮）")
    void differentRunsHaveDifferentRunIds() {
        SysFile turn1 = artifactService.save(artifact("data.csv", "https://oss/1.csv"), 1L, "s-1", "run-A");
        SysFile turn2 = artifactService.save(artifact("data.tsv", "https://oss/2.tsv"), 1L, "s-1", "run-B");

        assertEquals("run-A", turn1.getRunId());
        assertEquals("run-B", turn2.getRunId());
    }

    @Test
    @DisplayName("历史每一行带上自己那一轮的 runId（多轮不串行）")
    void historyRowsCarryTheirOwnRunId() {
        List<ChatHistory> rows = List.of(
                row(UserMessage.from("生成一份 csv"), "run-A"),
                row(AiMessage.from("已生成 data.csv。"), "run-A"),
                row(UserMessage.from("再生成一份 tsv"), "run-B"),
                row(AiMessage.from("已生成 data.tsv。"), "run-B"));
        when(chatMemoryMapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(rows);

        List<MessageVO> history = chatMemoryService.getHistoryBySessionId("s-1");

        assertEquals(4, history.size());
        assertEquals(List.of("run-A", "run-A", "run-B", "run-B"),
                history.stream().map(MessageVO::getRunId).toList(),
                "每一轮的行都要带自己那一轮的 runId —— 一旦串行，产物就会被挂到没产出它的那一轮");
    }

    // ============ 验收 2：两条链路对得上（锁定 JSON 字段名） ============

    @Test
    @DisplayName("产物列表的 JSON 里有 runId 字段（前端按名读取）")
    void artifactJsonExposesRunId() throws Exception {
        SysFile saved = artifactService.save(artifact("data.csv", "https://oss/1.csv"), 1L, "s-1", "run-c1-0f3a");

        String json = JSON.writeValueAsString(saved);
        assertTrue(json.contains("\"runId\":\"run-c1-0f3a\""),
                "GET /api/artifact 返回的是 SysFile 本体，字段名变了前端就匹配不上：" + json);
        // 前端靠 id 或 url 去重：这两个字段必须还在
        assertTrue(json.contains("\"fileName\":\"data.csv\""), json);
        assertTrue(json.contains("\"fileUrl\":\"https://oss/1.csv\""), json);
    }

    @Test
    @DisplayName("历史行的 JSON 里有 runId 字段")
    void historyJsonExposesRunId() throws Exception {
        when(chatMemoryMapper.getAllByMemoryIdAndUserId(any(), anyLong()))
                .thenReturn(List.of(row(AiMessage.from("已生成。"), "run-c1-0f3a")));

        MessageVO vo = chatMemoryService.getHistoryBySessionId("s-1").get(0);

        assertTrue(JSON.writeValueAsString(vo).contains("\"runId\":\"run-c1-0f3a\""),
                "GET /api/history 每行都要有 runId，否则前端拿不到归属依据");
    }

    // ============ 验收 3：老数据没有 runId 也不能炸 ============

    @Test
    @DisplayName("老产物：runId 为 null 时照常落库，字段为空")
    void legacyArtifactWithoutRunId() {
        SysFile saved = artifactService.save(artifact("历史遗留.tmp", "https://oss/old.tmp"), 1L, "s-1", null);

        assertNotNull(saved, "拿不到 runId 不能影响落库：文件已经生成好了");
        assertNull(saved.getRunId(), "归属不明就是 null，前端放进面板而不是冒充某一轮");
    }

    @Test
    @DisplayName("老历史行：runId 为 null 时不报错，且不影响其他行")
    void legacyHistoryRowWithoutRunId() {
        when(chatMemoryMapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(List.of(
                row(UserMessage.from("很久以前的问题"), null),
                row(AiMessage.from("很久以前的回答"), null)));

        List<MessageVO> history = assertDoesNotThrow(() -> chatMemoryService.getHistoryBySessionId("s-1"));

        assertEquals(2, history.size());
        assertNull(history.get(0).getRunId());
        assertNull(history.get(1).getRunId());
    }

    @Test
    @DisplayName("脏行（content 为 null）被跳过，且不打乱后续行的 runId 归属")
    void dirtyRowDoesNotShiftRunIds() {
        List<ChatHistory> rows = new ArrayList<>();
        rows.add(row(UserMessage.from("第一轮"), "run-A"));
        rows.add(ChatHistory.builder().type("AI").content(null).runId("run-A").build()); // 脏行
        rows.add(row(AiMessage.from("第二轮回答"), "run-B"));
        when(chatMemoryMapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(rows);

        List<MessageVO> history = chatMemoryService.getHistoryBySessionId("s-1");

        // 脏行被丢掉，但不能连带把 runId 也错配到别的行上
        assertEquals(2, history.size());
        assertEquals(List.of("run-A", "run-B"), history.stream().map(MessageVO::getRunId).toList());
    }

    // ============ 落库链路：RunContext → chat_memory.run_id ============

    @Test
    @DisplayName("一次运行写入的每条历史消息都带上同一个 runId")
    void memoryStoreWritesRunIdOnEveryRow() {
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        PgChatMemoryStore store = new PgChatMemoryStore(memoryService, new AgentProperties());
        when(memoryService.getLastMessageJson(any(), anyLong())).thenReturn(null);

        RunContext runContext = new RunContext(1L, "s-1", true, Map.of(), "run-A");
        List<ChatMessage> messages = List.of(
                UserMessage.from("生成一份 csv"),
                AiMessage.from("已生成 data.csv。"));

        store.updateMessages(runContext, "s-1", messages);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryService).insertBatch(captor.capture(), eq(1L));

        List<ChatHistory> inserted = captor.getValue();
        assertEquals(2, inserted.size());
        for (ChatHistory row : inserted) {
            assertEquals("run-A", row.getRunId(), "同一轮写入的每一行都要带同一个 runId");
        }
    }

    // ============ 辅助 ============

    private static Map<String, Object> artifact(String name, String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("url", url);
        map.put("size", 436);
        map.put("extension", "csv");
        return map;
    }

    private static ChatHistory row(ChatMessage message, String runId) {
        return ChatHistory.builder()
                .type(message.type().name())
                .content(ChatMessageSerializer.messageToJson(message))
                .runId(runId)
                .build();
    }
}
