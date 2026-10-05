package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.service.impl.ChatMemoryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话链路读历史的「条数上限」行为（2026-10-06 新增）。
 * <p>
 * 为什么要有这个测试：SQL 的 {@code LIMIT} 不接受 0 或负数 ——
 * {@code LIMIT 0} 返回空结果（表现为"模型完全失忆"），{@code LIMIT -1} 直接报错。
 * 而调用方的语义是「{@code <= 0} 表示不限制」，这个分流必须在 Java 层完成。
 * 这个 bug 用 mock 单测能抓到，但它一旦发生就是"长会话突然失忆"的线上事故，值得钉死。
 */
@DisplayName("对话链路读历史 —— 条数上限与「不限制」的分流")
class ChatMemoryRecentForChatTest {

    @Test
    @DisplayName("limit > 0：走限量查询，把 limit 透传给 SQL")
    void positiveLimitUsesLimitedQuery() {
        ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
        ChatMemoryServiceImpl service = service(mapper);
        when(mapper.getRecentForChat(any(), anyLong(), anyInt())).thenReturn(List.of());

        service.getRecentForChat("s-1", 1L, 200);

        verify(mapper).getRecentForChat(eq("s-1"), eq(1L), eq(200));
        verify(mapper, never()).getAllByMemoryIdAndUserId(any(), anyLong());
    }

    @Test
    @DisplayName("limit <= 0：退化为全量查询，绝不能把 0 或负数透传进 SQL 的 LIMIT")
    void nonPositiveLimitFallsBackToFullQuery() {
        for (int limit : new int[]{0, -1}) {
            ChatMemoryMapper mapper = mock(ChatMemoryMapper.class);
            ChatMemoryServiceImpl service = service(mapper);
            List<ChatHistory> rows = List.of(ChatHistory.builder().type("AI").build());
            when(mapper.getAllByMemoryIdAndUserId(any(), anyLong())).thenReturn(rows);

            List<ChatHistory> result = service.getRecentForChat("s-1", 1L, limit);

            verify(mapper).getAllByMemoryIdAndUserId(eq("s-1"), eq(1L));
            // 关键：限量查询一次都没被调用 —— LIMIT 0 会返回 0 行（模型直接失忆），
            // LIMIT -1 会被 PostgreSQL 拒绝
            verify(mapper, never()).getRecentForChat(any(), anyLong(), anyInt());
            org.junit.jupiter.api.Assertions.assertEquals(1, result.size(),
                    "limit=" + limit + " 的语义是「不限制」，必须拿回全部历史");
        }
    }

    private static ChatMemoryServiceImpl service(ChatMemoryMapper mapper) {
        ChatMemoryServiceImpl service = new ChatMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        return service;
    }
}
