package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.service.impl.ChatMemoryServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 🔴 缺列降级（2026-10-06）。
 *
 * <p>用户报「聊天记录好像被删了 + seq 跳号」。查下来本类新增的两条核心链路
 * （拉历史 / 读记忆）都直接 {@code SELECT superseded_by}，
 * 而 {@code docs/sql/013} 没执行时 PostgreSQL 抛
 * {@code column "superseded_by" does not exist} → 两条链路一起 500 → 首页白屏。
 *
 * <p>项目惯例一直是「DB 查询失败 → 降级放行 + 明确日志」（见 {@code QuotaServiceImpl} 两处），
 * 我这两处**没遵守**。这里把降级行为钉死。
 */
@DisplayName("superseded_by 缺列时必须降级，不能让核心链路 500")
class SupersededByFallbackTest {

    private static final String SESSION = "sess-1";
    private static final Long USER_ID = 1L;

    private final ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
    private final ChatMemoryServiceImpl service = service(mapper);

    private static ChatMemoryServiceImpl service(ChatMemoryMapper mapper) {
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }

    /**
     * 缺列时的真实异常形态。
     * <p>
     * ⚠️ 这里用普通 RuntimeException 当 cause 而不是 {@code PSQLException}：
     * 本项目 pgjdbc 版本的 {@code PSQLException} <b>没有单参 String 构造器</b>
     * （只有 {@code (String, ServerErrorMessage)} 等），硬凑会编译失败。
     * 关键点在于 Spring 的 {@code BadSqlGrammarException} 构造时会把 cause 的 message
     * 拼进自己的 getMessage()（形如 {@code task; SQL [...]; <cause message>}），
     * 而降级判断正是看 getMessage() 里有���有 {@code superseded_by}。
     */
    private static BadSqlGrammarException sqlError(String pgMessage) {
        return new BadSqlGrammarException("query", "select ... ", new java.sql.SQLException(pgMessage));
    }

    private static BadSqlGrammarException missingColumn() {
        return sqlError("ERROR: column \"superseded_by\" does not exist");
    }

    @Test
    @DisplayName("拉历史：缺列时改查不带该列的 SQL，而不是抛异常")
    void historyFallsBackWhenColumnMissing() {
        when(mapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenThrow(missingColumn());
        List<ChatHistory> rows = List.of(ChatHistory.builder().id(1L).type("AI").build());
        when(mapper.getAllByMemoryIdAndUserIdWithoutSuperseded(any(), anyLong())).thenReturn(rows);

        assertEquals(rows, service.getByMemoryIdAndUserId(SESSION, USER_ID),
                "缺列时必须仍能读到历史 —— 抛异常等于告诉用户「记录没了」");
        verify(mapper).getAllByMemoryIdAndUserIdWithoutSuperseded(SESSION, USER_ID);
    }

    @Test
    @DisplayName("记忆加载：缺列时退化为 getRecentForChat（不排除被替代的版本），但仍能读")
    void memoryLoadFallsBackWhenColumnMissing() {
        when(mapper.getActiveForChat(any(), anyLong(), anyInt())).thenThrow(missingColumn());
        List<ChatHistory> rows = List.of(ChatHistory.builder().id(1L).type("AI").build());
        when(mapper.getRecentForChat(any(), anyLong(), anyInt())).thenReturn(rows);

        assertEquals(rows, service.getActiveForChat(SESSION, USER_ID, 200),
                "记忆加载失败会让整次对话报错，代价远大于「版本切换不生效」");
        verify(mapper).getRecentForChat(SESSION, USER_ID, 200);
    }

    @Test
    @DisplayName("列存在时走正常查询，绝不降级（降级是兜底不是常态）")
    void normalPathNeverFallsBack() {
        when(mapper.getActiveForChat(any(), anyLong(), anyInt())).thenReturn(List.of());

        service.getActiveForChat(SESSION, USER_ID, 200);

        verify(mapper, never()).getRecentForChat(any(), anyLong(), anyInt());
        verify(mapper, never()).getAllByMemoryIdAndUserIdWithoutSuperseded(any(), anyLong());
    }

    @Test
    @DisplayName("其他 SQL 异常（连接断了、权限不足）照常抛 —— 不能伪装成「版本切换不可用」")
    void unrelatedSqlErrorStillPropagates() {
        when(mapper.getAllByMemoryIdAndUserId(any(), anyLong()))
                .thenThrow(sqlError("ERROR: connection refused"));

        assertThrows(BadSqlGrammarException.class,
                () -> service.getByMemoryIdAndUserId(SESSION, USER_ID),
                "只认「superseded_by 缺列」这一种；把运维故障伪装成功能降级会让人更晚才发现问题");
    }

    @Test
    @DisplayName("端到端：缺列时历史仍完整（降级后 supersededBy 为 null = 未替代）")
    void endToEndHistoryStillCompleteWhenMissing() {
        when(mapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenThrow(missingColumn());
        when(mapper.getAllByMemoryIdAndUserIdWithoutSuperseded(any(), anyLong())).thenReturn(List.of(
                ChatHistory.builder().id(1L).type("USER")
                        .content(ChatMessageSerializer.messageToJson(UserMessage.from("问题A"))).build(),
                ChatHistory.builder().id(2L).type("AI")
                        .content(ChatMessageSerializer.messageToJson(AiMessage.from("回答A"))).build()));

        List<ChatHistory> rows = service.getByMemoryIdAndUserId(SESSION, USER_ID);

        assertEquals(2, rows.size(), "两条历史都在 —— 用户看到的就是「记录还在」，不是被删");
    }

    /** 记忆 store 侧：缺列降级后仍能反序列化成消息（否则对话直接 500） */
    @Test
    @DisplayName("PgChatMemoryStore 在降级路径上不抛异常")
    void memoryStoreWorksOnFallbackPath() {
        ChatMemoryService memoryService = mock(ChatMemoryService.class);
        when(memoryService.getActiveForChat(any(), anyLong(), anyInt())).thenReturn(List.of(
                ChatHistory.builder().id(1L).type("USER")
                        .content(ChatMessageSerializer.messageToJson(UserMessage.from("问题A"))).build(),
                ChatHistory.builder().id(2L).type("AI")
                        .content(ChatMessageSerializer.messageToJson(AiMessage.from("回答A"))).build()));
        PgChatMemoryStore store = new PgChatMemoryStore(memoryService, new AgentProperties());

        List<ChatMessage> messages = store.getMessages(
                new RunContext(USER_ID, SESSION, false, Map.of(), "run-1"), SESSION);

        assertEquals(2, messages.size());
    }
}
