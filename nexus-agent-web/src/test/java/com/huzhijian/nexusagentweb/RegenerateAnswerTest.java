package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「重新生成」的落库与上下文裁剪（2026-10-06）。
 *
 * <p>用户反馈的现象是**显示层**的（刷新后变成「问题A 回答A / 问题A 回答B」两轮平铺），
 * 但真正的坑在服务端：每轮都从库里读全量历史，不处理的话两版回答会<b>同时进模型上下文</b> ——
 * 白烧 token、让模型困惑，而且前端的 n/n 切换会形同虚设（切回 1/2 接着聊，
 * 模型记得的仍是 2/2）。这里把三条不变式钉死。
 */
@DisplayName("重新生成 —— 不重复存提问 / 标记旧版本 / 记忆只取现行版本")
class RegenerateAnswerTest {

    private static final String SESSION = "sess-1";
    private static final Long USER_ID = 1L;
    private static final String NEW_RUN = "run-new";
    /** 历史里那条旧回答的 id（前端点重新生成时所在的回答） */
    private static final String OLD_ANSWER_ID = "1001";
    private static final Long OLD_ANSWER_ID_LONG = 1001L;
    private static final Long NEW_ANSWER_ID_LONG = 2002L;

    private ChatMemoryService memoryService;
    private PgChatMemoryStore store;

    private ChatMemoryService service() {
        memoryService = mock(ChatMemoryService.class);
        // 库里最后一条 = 那条旧回答（重新生成时锚点会落空 → 走内容去重，这是预期路径）
        when(memoryService.getLastMessageJson(any(), anyLong())).thenReturn(
                ChatMessageSerializer.messageToJson(AiMessage.from("回答A")));
        // 本次运行新写入的 AI 回答 id
        when(memoryService.findFirstAiMessageIdOfRun(any(), anyLong(), anyString()))
                .thenReturn(NEW_ANSWER_ID_LONG);
        store = new PgChatMemoryStore(memoryService, new AgentProperties());
        return memoryService;
    }

    private RunContext regenerateContext() {
        return new RunContext(USER_ID, SESSION, false, Map.of(), NEW_RUN, OLD_ANSWER_ID);
    }

    @Test
    @DisplayName("重新生成（同一问题）：库里已有的提问不重复写，只写新回答")
    void doesNotPersistDuplicateUserMessage() {
        service();
        // 库里有「问题A + 回答A」；本次记忆 = 库里的问题A + 本次 add 的问题A + 新回答B
        when(memoryService.getRecentMessageJson(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        ChatMessageSerializer.messageToJson(UserMessage.from("问题A")),
                        ChatMessageSerializer.messageToJson(AiMessage.from("回答A"))));

        store.updateMessages(regenerateContext(), SESSION, List.of(
                UserMessage.from("问题A"),
                UserMessage.from("问题A"),
                AiMessage.from("回答B")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryService).insertBatch(captor.capture(), eq(USER_ID));
        List<ChatHistory> inserted = captor.getValue();
        assertEquals(List.of("AI"), types(inserted),
                "重复的提问不该再存一遍（模型会看到「同一问题问两遍」，前端也没法归成一组做 n/n 切换）");
    }

    /**
     * 🔴 回归护栏：fronted 2026-10-06 指出的真 bug。
     * <p>
     * 「切到旧版本再发消息」时，用户发的是<b>新提问</b>。
     * 原实现只要 {@code regenerateFromMessageId} 有值就过滤掉所有 USER 行 ——
     * 结果<b>新提问被一起丢掉</b>，用户发的话凭空消失。
     * <p>
     * 正确做法：重复与否该由「库里是否已有这条提问」判断，而这正是
     * {@code resolveInsertStartIndex} 的内容去重在做的事，不该另加显式过滤。
     */
    @Test
    @DisplayName("切旧版本后发【新提问】：新提问必须被存下来（不能被 regenerateFromMessageId 吃掉）")
    void keepsBrandNewUserMessageOnOldBranch() {
        service();
        when(memoryService.getRecentMessageJson(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(
                        ChatMessageSerializer.messageToJson(UserMessage.from("问题A")),
                        ChatMessageSerializer.messageToJson(AiMessage.from("回答A"))));

        // 传旧版本的 id 作基线，但 messages 里是一条**全新**的提问
        store.updateMessages(regenerateContext(), SESSION, List.of(
                UserMessage.from("问题A"),
                UserMessage.from("全新问题C"),
                AiMessage.from("回答D")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatHistory>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryService).insertBatch(captor.capture(), eq(USER_ID));
        assertEquals(List.of("USER", "AI"), types(captor.getValue()),
                "新提问必须落库 —— 丢了它等于用户说的话凭空消失，是最严重的数据丢失");
    }

    @Test
    @DisplayName("重新生成：把旧版本标记为被新回答替代（这一步不做，n/n 切换就是假的）")
    void marksOldVersionSuperseded() {
        service();

        store.updateMessages(regenerateContext(), SESSION, List.of(
                UserMessage.from("问题A"),
                UserMessage.from("问题A"),
                AiMessage.from("回答B")));

        // sinceId = 用户点的那条旧回答，newId = 本次新回答，excludeRunId = 本次 runId
        verify(memoryService).markSupersededSince(eq(SESSION), eq(USER_ID),
                eq(OLD_ANSWER_ID_LONG), eq(NEW_ANSWER_ID_LONG), eq(NEW_RUN));
    }

    @Test
    @DisplayName("普通发问：完全不碰 superseded（没有重新生成就没有替代关系）")
    void normalChatNeverMarksSuperseded() {
        service();

        store.updateMessages(new RunContext(USER_ID, SESSION, false, Map.of(), "run-plain"),
                SESSION, List.of(UserMessage.from("问题A"), AiMessage.from("回答A")));

        verify(memoryService, never()).markSupersededSince(any(), anyLong(), anyLong(), anyLong(), anyString());
        verify(memoryService, never()).findFirstAiMessageIdOfRun(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("重新生成：regenerateFromMessageId 不是合法 id 时降级，不抛异常")
    void malformedRegenerateIdDegradesGracefully() {
        service();

        // 非法 id：新回答照样落库并返回给用户，只是不标记旧版本
        store.updateMessages(new RunContext(USER_ID, SESSION, false, Map.of(), NEW_RUN, "not-a-number"),
                SESSION, List.of(UserMessage.from("问题A"), UserMessage.from("问题A"), AiMessage.from("回答B")));

        verify(memoryService).insertBatch(any(), eq(USER_ID));
        verify(memoryService, never()).markSupersededSince(any(), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("重新生成：本次没有 AI 回答入库时不标记（没有新版本可当替代者）")
    void noNewAnswerNoMarking() {
        service();
        when(memoryService.findFirstAiMessageIdOfRun(any(), anyLong(), anyString())).thenReturn(null);

        store.updateMessages(regenerateContext(), SESSION, List.of(
                UserMessage.from("问题A"), UserMessage.from("问题A"), AiMessage.from("回答B")));

        verify(memoryService, never()).markSupersededSince(any(), anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("记忆加载走 getActiveForChat（排除被替代的版本），不走历史接口那条")
    void memoryLoadExcludesSuperseded() {
        service();
        when(memoryService.getActiveForChat(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        store.getMessages(regenerateContext(), SESSION);

        verify(memoryService).getActiveForChat(eq(SESSION), eq(USER_ID), org.mockito.ArgumentMatchers.anyInt());
        // 🔴 关键：绝不能用历史接口那条 —— 它返回全部版本，被替代的旧回答会进模型上下文，
        // 于是「切回 1/2 继续问」时模型仍记得 2/2，n/n 切换形同虚设
        verify(memoryService, never()).getByMemoryIdAndUserId(any(), anyLong());
        verify(memoryService, never()).getRecentForChat(any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("记忆窗口上限只是兜底 —— 别让它小到让模型静默失忆")
    void memoryWindowUpperBoundIsOnlyASafetyNet() {
        var caps = com.huzhijian.nexusagentweb.model.ModelCapabilities.of(new Model());
        int effective = caps.memoryWindow(new AgentProperties().getMemory().getMaxTokens());
        // ⚠️ 2026-10-06：原来是断言 <= 32768，依据「窗口大就慢」，该因果已被线上数据推翻
        //（真瓶颈是 skillResolve 与 CHAT_MEMORY 重复查库）。现在只保留下限——
        // 窗口过小会让模型静默丢历史，用户却以为是自己聊得太多。
        assertTrue(effective >= 80_000,
                "实际生效的记忆窗口只有 " + effective + " token，太小了 —— 会静默丢历史");
        assertFalse(caps.vision(), "默认仍是不支持视觉");
    }

    private static List<String> types(List<ChatHistory> rows) {
        return rows.stream().map(r -> String.valueOf(r.getType())).toList();
    }
}
