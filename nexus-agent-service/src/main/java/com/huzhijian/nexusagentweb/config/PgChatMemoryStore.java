package com.huzhijian.nexusagentweb.config;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** 仅用于「按 JSON 树」比较消息是否同一条，见 {@link #canonical} */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 锚点失配时，拿库中最近多少条做内容去重 */
    private static final int RECENT_DEDUP_LIMIT = 50;

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
     * 增量定位方式：**锚点法** —— 取库中最后一条消息，在传入列表里找到它的位置，
     * 其后的就是新增部分。
     * <p>
     * 为什么不用「比较条数」（原实现）：记忆窗口在 token 超限时会**淘汰旧消息**，
     * 传入列表不再单调增长，条数比较会永远判定为「没有新增」，
     * 结果长会话的新消息永远写不进库。锚点法不受淘汰影响，也不会丢历史。
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

        // 系统消息不入库：它由 @SystemMessage 每轮重新提供，存下来纯属冗余。
        // （LangChain4j 的 TokenWindowChatMemory 会一直保留系统消息，
        //   窗口很小时它甚至会挤掉所有对话消息，这里先把它们剔掉再算增量。）
        List<ChatMessage> persistable = new ArrayList<>();
        for (ChatMessage message : list) {
            if (message != null && message.type() != ChatMessageType.SYSTEM) {
                persistable.add(message);
            }
        }

        int startIndex = resolveInsertStartIndex(runContext, sessionId, persistable);
        if (startIndex >= persistable.size()) {
            return;
        }

        ArrayList<ChatHistory> insertList = new ArrayList<>();
        for (ChatMessage chatMessage : persistable.subList(startIndex, persistable.size())) {
            // 附件元数据直接来自 RunContext，不再依赖 ThreadLocal
            if (chatMessage instanceof UserMessage userMessage && runContext.hasMessageMetadata()) {
                userMessage.attributes().putAll(runContext.messageMetadata());
            }
            String jsonString = ChatMessageSerializer.messageToJson(chatMessage);
            ChatHistory chatHistory = ChatHistory.builder()
                    .sessionId(sessionId)
                    .type(chatMessage.type().name())
                    .content(jsonString)
                    // 产物归属（方案 B）：把本次运行的 runId 写进每一行历史消息。
                    // 前端拿它与 sys_file.run_id 做字符串相等匹配，刷新后也能把产物挂回正确那一轮。
                    // 老数据（本列上线前写的）为 null，前端按「归属不明」跳过。
                    .runId(runContext.runId())
                    .build();
            insertList.add(chatHistory);
        }
        chatMemoryService.insertBatch(insertList, userId);
    }

    /**
     * 算出「传入列表里从第几条开始是新增的」。
     * <p>
     * 优先用锚点（库中最后一条消息）定位；锚点失配时按内容与库中最近若干条去重。
     * 两条路都不依赖「条数单调增长」，因此记忆窗口淘汰旧消息后依然正确。
     */
    private int resolveInsertStartIndex(RunContext runContext, Object sessionId, List<ChatMessage> persistable) {
        if (persistable.isEmpty()) {
            return 0;
        }
        String anchorJson = chatMemoryService.getLastMessageJson(sessionId, runContext.userId());
        if (anchorJson == null) {
            log.debug("sessionId={} 库中无历史，全量写入 {} 条", sessionId, persistable.size());
            return 0;
        }
        // 从后往前找：正常情况下锚点就在倒数第二、三条附近，能快速命中
        String anchor = canonical(anchorJson);
        if (anchor != null) {
            for (int i = persistable.size() - 1; i >= 0; i--) {
                if (anchor.equals(canonical(ChatMessageSerializer.messageToJson(persistable.get(i))))) {
                    log.debug("sessionId={} 锚点命中于第 {} 条（共 {} 条）", sessionId, i, persistable.size());
                    return i + 1;
                }
            }
        }
        return dedupStartIndex(runContext, sessionId, persistable);
    }

    /**
     * 锚点失配时的兜底：把传入消息与「库中最近 N 条」按内容比对，
     * 只保留库里没有的**尾部**消息。
     * <p>
     * 什么时候会走到这里：记忆窗口很小（例如小于系统提示词长度）时，
     * 窗口里的消息可能已被整体替换，锚点不在传入列表中。
     * 完全靠条数比较在这种场景下会「永远判定没有新增」，导致新消息写不进库。
     */
    private int dedupStartIndex(RunContext runContext, Object sessionId, List<ChatMessage> persistable) {
        List<String> recent = chatMemoryService.getRecentMessageJson(sessionId, runContext.userId(), RECENT_DEDUP_LIMIT);
        Set<String> stored = new HashSet<>();
        for (String json : recent) {
            String canon = canonical(json);
            if (canon != null) {
                stored.add(canon);
            }
        }
        int start = persistable.size();
        for (int i = persistable.size() - 1; i >= 0; i--) {
            String canon = canonical(ChatMessageSerializer.messageToJson(persistable.get(i)));
            if (canon != null && stored.contains(canon)) {
                break; // 命中已存过的消息，说明它之前都是旧的
            }
            start = i;
        }
        log.warn("sessionId={} 锚点失配（库中最近 {} 条 / 传入 {} 条），按内容去重后从第 {} 条开始写入",
                sessionId, recent.size(), persistable.size(), start);
        return start;
    }

    /**
     * 把消息 JSON 规范化成「键序无关」的字符串，用于内容比较。
     * <p>
     * ⚠️ 不能直接比字符串：`content` 是 jsonb 列，PostgreSQL 会规范化键序与空白，
     * 回读文本与 Java 序列化结果不会逐字节相同。必须递归排序对象键后再比较。
     */
    private static String canonical(String json) {
        if (json == null) {
            return null;
        }
        try {
            return canonicalize(MAPPER.readTree(json)).toString();
        } catch (Exception e) {
            // 规范化失败会让锚点比对失效（进而整段历史重复写入），不能静默
            log.warn("消息 JSON 规范化失败，锚点比对将跳过该条。json 长度={}",
                    json == null ? -1 : json.length(), e);
            return null;
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) {
                sorted.set(name, canonicalize(objectNode.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node;
    }

    public void deleteMessages(Object sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("会话ID不能为NULL/空");
        }
        chatMemoryService.delByMemoryId(sessionId);
    }
}
