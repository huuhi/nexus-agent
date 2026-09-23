package com.huzhijian.nexusagentweb.config;


import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/3/25
 * 说明: 聊天记忆的 PostgreSQL 存储。
 * <p>
 * 本类自身**不再实现** {@link ChatMemoryStore}，而是通过 {@link #forRun(RunContext)}
 * 为每次对话生成一个绑定了上下文的实例。
 * <p>
 * 改动原因（原实现有两个跨线程缺陷）：
 * <ol>
 *   <li>附件元数据用 {@code MessageMetadataContext}（ThreadLocal）传递。写入发生在请求线程，
 *       读取发生在**流式回调线程**，取不到值 → 上传文件后聊天记录里文件信息丢失。</li>
 *   <li>用户 ID 靠 Redis 的 {@code session:<id>} 反查。该 key 的 TTL 只有 5 分钟，
 *       过期后 {@code Long.valueOf(null)} 直接抛异常 → 聊久一点聊天记录就写不进库。</li>
 * </ol>
 * 现在两者都改为从 {@link RunContext} 显式获取。
 * <p>
 * 另：原方法上的 {@code @Transactional} 已移除。原因是它在本类被 LangChain4j 通过内部实例调用时
 * 根本不生效（自调用绕过 Spring 代理），留着只会误导；且持久化本身只是「一条批量 INSERT」，
 * 单条语句本就是原子的，不需要额外事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgChatMemoryStore {

    private final ChatMemoryService chatMemoryService;

    /**
     * 为一次对话生成记忆存储实例，把跨线程需要的数据（用户 ID、附件元数据）绑定进去。
     * 在请求线程上调用一次，随后交给 LangChain4j 使用。
     */
    public ChatMemoryStore forRun(RunContext runContext) {
        return new RunScopedChatMemoryStore(runContext);
    }

    /**
     * 绑定单次对话上下文的 ChatMemoryStore 实现。
     * 因为持有 RunContext，所以不需要任何 ThreadLocal。
     */
    private class RunScopedChatMemoryStore implements ChatMemoryStore {

        private final RunContext runContext;

        RunScopedChatMemoryStore(RunContext runContext) {
            this.runContext = runContext;
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return PgChatMemoryStore.this.getMessages(runContext, memoryId);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            PgChatMemoryStore.this.updateMessages(runContext, memoryId, messages);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            PgChatMemoryStore.this.deleteMessages(memoryId);
        }
    }

    /**
     * 读取某个会话的历史消息。
     * <p>
     * ⚠️ 必须同时按 user_id 过滤：sessionId 由客户端传入，不过滤就等于
     * 「知道别人的 sessionId 就能把别人的聊天记录读进自己的上下文」。
     * 这与 /api/history/{sessionId} 接口的越权修复是同一件事。
     * 若 sessionId 不属于当前用户，这里返回空列表，模型从零开始，不会泄露内容。
     */
    public List<ChatMessage> getMessages(RunContext runContext, Object memoryId) {
        if (memoryId == null) {
            return List.of();
        }
        List<ChatHistory> chatMemories =
                chatMemoryService.getByMemoryIdAndUserId(memoryId, runContext.userId());
        if (chatMemories == null || chatMemories.isEmpty()) {
            return List.of();
        }
        return chatMemories.stream()
                .map(entity -> ChatMessageDeserializer.messageFromJson(entity.getContent().toString()))
                .toList();
    }

    /**
     * 持久化消息（只写增量）。
     * <p>
     * 增量方式：与库中已有的条数比较，只插入尾部新增的部分。
     */
    public void updateMessages(RunContext runContext, Object sessionId, List<ChatMessage> list) {
        if (sessionId == null) {
            throw new IllegalArgumentException("会话ID不能为NULL/空");
        }
        Long userId = runContext.userId();
        if (userId == null) {
            // 正常情况下拦截器已保证 userId 存在；这里兜底，避免写入无归属的脏数据
            throw new UnauthorizedException("会话缺少用户归属，拒绝写入聊天记录");
        }

        ArrayList<ChatHistory> insertList = new ArrayList<>();

        // 只添加增量数据
        int count = chatMemoryService.getCountBySessionID(sessionId.toString());
        log.debug("sessionId={}, 库中消息数={}, 传入消息数={}", sessionId, count, list.size());

        if (list.size() > count) {
            List<ChatMessage> needAdd = list.subList(count, list.size());
            for (ChatMessage chatMessage : needAdd) {
                // 附件元数据直接来自 RunContext，不再依赖 ThreadLocal
                if (chatMessage instanceof UserMessage userMessage && runContext.hasMessageMetadata()) {
                    userMessage.attributes().putAll(runContext.messageMetadata());
                }
                String jsonString = ChatMessageSerializer.messageToJson(chatMessage);
                ChatHistory chatHistory = ChatHistory.builder()
                        .sessionId(sessionId)
                        .type(chatMessage.type().name())
                        .content(jsonString)
                        .build();
                insertList.add(chatHistory);
            }
        }
        chatMemoryService.insertBatch(insertList, userId);
    }

    public void deleteMessages(Object sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("会话ID不能为NULL/空");
        }
        chatMemoryService.delByMemoryId(sessionId);
    }
}
