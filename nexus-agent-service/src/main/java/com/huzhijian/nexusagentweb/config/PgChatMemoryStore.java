package com.huzhijian.nexusagentweb.config;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huzhijian.nexusagentweb.context.RunContext;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
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
    private final AgentProperties agentProperties;

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

        /**
         * 🔴 请求级缓存（2026-10-06）：本次运行内已从库里读到的消息。
         * <p>
         * 线上实测 {@code CHAT_MEMORY msgs=97 load=721ms} 在<b>同一 runId 下出现 3 次</b>
         * （721 + 776 + 895 ≈ 2.4 秒）—— {@code TokenWindowChatMemory} 在
         * {@code AiServices.chat()} 内部会多次触碰 {@code messages()}（建记忆、裁剪、写入前再确认），
         * 每一次都穿透到 DB。
         * <p>
         * 为什么这个缓存是<b>安全</b>的：本类实例是 {@link #forRun} 每次新建的
         * {@code RunScopedChatMemoryStore}，<b>一个请求一个实例</b>，不会跨请求复用；
         * 而 {@link #updateMessages} 会把 {@link #messagesStale} 置真，让写入后的读取重新查库 ——
         * 所以 LangChain4j 写入过程中看到的一定是最新数据，不会读到过期快照。
         */
        private List<ChatMessage> cachedMessages;
        private boolean messagesStale;

        RunScopedChatMemoryStore(RunContext runContext) {
            this.runContext = runContext;
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            if (cachedMessages != null && !messagesStale) {
                log.debug("本次运行内命中消息缓存，跳过查库：runId={} msgs={}",
                        runContext.runId(), cachedMessages.size());
                return cachedMessages;
            }
            cachedMessages = PgChatMemoryStore.this.getMessages(runContext, memoryId);
            messagesStale = false;
            return cachedMessages;
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            // 🔴 写入即失效：之后的任何读取都必须回库，否则 LangChain4j 内存里
            // 已经追加了新消息、库里也变了，缓存却还是旧快照 —— 会导致历史错乱。
            messagesStale = true;
            cachedMessages = null;
            PgChatMemoryStore.this.updateMessages(runContext, memoryId, messages);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            messagesStale = true;
            cachedMessages = null;
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
        long t0 = System.nanoTime();
//        🔴 只取「当前生效」的消息：排除被「重新生成」替代掉的旧版本回答。
//        ⚠️ 这里的查询与历史接口**刻意不同**（那边用 getAllByMemoryIdAndUserId 返回全部，
//        因为前端要做 n/n 切换）。若这里也返回全部，模型上下文会同时看到「问过两遍、答过两遍」：
//        既白烧 token（直接踩首字延迟这条线），又让模型困惑；
//        而且用户切回 1/2 接着聊时，模型记得的仍是 2/2 的内容 —— 切换就形同虚设。
//        ⚠️ 方向也踩过：直接 `order by create_at limit N` 拿到的是**最早**的 N 条，正好相反。
        List<ChatHistory> chatMemories = chatMemoryService.getActiveForChat(
                memoryId, runContext.userId(), agentProperties.getMemory().getMaxHistoryMessages());
        if (chatMemories == null || chatMemories.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> messages = chatMemories.stream()
                .map(entity -> ChatMessageDeserializer.messageFromJson(entity.getContent().toString()))
                .toList();
//        🔴 首字延迟排查的关键一行：首字慢最常见的原因就是**送给模型的历史太大**
//        （模型要先做完 prefill 才吐得出第一个字，历史越长越慢，而且是"越聊越慢"）。
//        有这一行就能直接读出「本次带了多少条历史 / 花了多久」，不必再猜。
//        条数撞上 maxHistoryMessages 上限时说明会话已经很长，可以考虑调小窗口。
        log.info("CHAT_MEMORY runId={} msgs={} load={}ms limit={}",
                runContext.runId(), messages.size(), (System.nanoTime() - t0) / 1_000_000L,
                agentProperties.getMemory().getMaxHistoryMessages());
        return messages;
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

//        🔴 2026-10-06 修正一个真 bug：这里原先有一段「重新生成就过滤掉所有 USER 行」。
//        那个判断是错的 —— fronted 指出「用户切到旧版本后发的是**新提问**，也会被一起丢掉」。
//        重复提问与新提问的区别**不该由 regenerateFromMessageId 决定**（它只该管分支基线），
//        而该由「这条提问库里是否已有」决定 —— 而这件事 resolveInsertStartIndex 已经做完了：
//          · 重新生成（同一问题）：锚点失配 → 内容去重 → 碰到库里那条问题A 就停 → 只写回答 ✅
//          · 切旧版本发新提问  ：去重时「问题C」不在库里，继续往前找到问题A 才停 → 写 [问题C, 回答D] ✅
//        显式过滤反而把第二种场景的新提问吃掉了。所以这里**什么都不做**，交给上面那段。
//        ⚠️ 代价是依赖 dedup 的比对（只比库里最近 50 条）—— 重复提问必在这 50 条内（它必须在窗口里），
//        够用；真要更严就得给提问做显式指纹，那是另一件事。
        List<ChatMessage> toInsert = persistable.subList(startIndex, persistable.size());

        ArrayList<ChatHistory> insertList = new ArrayList<>();
        for (ChatMessage chatMessage : toInsert) {
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

        // 🔴 重新生成的最后一步：让「旧版本」出局，模型上下文与 n/n 切换才对得上。
        // 必须在插入**之后**做 —— 新版本的 id 是插入才产生的，它是 superseded_by 的目标值。
        markSupersededIfRegenerate(runContext, sessionId, userId);
    }

    /**
     * 重新生成后把旧版本标记为被替代（2026-10-06）。
     * <p>
     * 不做这一步会怎样：被替代的旧回答仍在模型上下文里 →
     * 用户切回 1/2 接着聊，模型记得的仍是 2/2 的内容，<b>n/n 切换形同虚设</b>。
     * <p>
     * 任何一步失败都<b>只记日志不抛</b>：此时用户已经拿到了新回答，
     * 把它变成一次 500 是最差的结果 —— 顶多下次记忆里多个旧版本，与本功能上线前一样。
     */
    private void markSupersededIfRegenerate(RunContext runContext, Object sessionId, Long userId) {
        if (!runContext.isRegenerate()) {
            return;
        }
        Long sinceId = parseMessageId(runContext.regenerateFromMessageId());
        if (sinceId == null) {
            // 前端传了但不是合法 id：按「不是重新生成」处理，不 500 也不猜
            log.warn("重新生成：regenerateFromMessageId 不是合法的消息 id，本次不标记旧版本。值={}",
                    runContext.regenerateFromMessageId());
            return;
        }
        try {
            Long newId = chatMemoryService.findFirstAiMessageIdOfRun(
                    sessionId, userId, runContext.runId());
            if (newId == null) {
                // 还没有 AI 回答落库（模型这次没产出正文）：没有「新版本」可标记，保持原样
                log.info("重新生成：本次运行没有 AI 回答入库，跳过标记旧版本。runId={}", runContext.runId());
                return;
            }
            int marked = chatMemoryService.markSupersededSince(
                    sessionId, userId, sinceId, newId, runContext.runId());
            log.info("重新生成：已标记 {} 条旧消息被替代。runId={} sinceId={} newId={}",
                    marked, runContext.runId(), sinceId, newId);
        } catch (Exception e) {
            log.warn("重新生成：标记旧版本失败（新回答已正常落库，不影响本次返回）。runId={} 原因={}",
                    runContext.runId(), e.getMessage(), e);
        }
    }

    /**
     * 把前端传来的消息 id 字符串解析成 Long。
     * <p>
     * ⚠️ 前端拿到的是<b>字符串</b>形态的雪花 ID（全局把 Long 序列化成 String），
     * 原样回传。这里必须容错：解析不了就返回 {@code null} 让调用方走「不标记」分支，
     * 绝不能因为一个字符串格式问题让整次对话失败。
     */
    private static Long parseMessageId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
