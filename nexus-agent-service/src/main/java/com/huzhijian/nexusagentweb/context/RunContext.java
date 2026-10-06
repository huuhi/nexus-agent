package com.huzhijian.nexusagentweb.context;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 一次对话运行的上下文。
 * <p>
 * 为什么需要它：LangChain4j 在**流式回调线程**上调用 {@code ChatMemoryStore.updateMessages}，
 * 与处理 HTTP 请求的线程不是同一个。而 {@code UserContextHolder} / {@code MessageMetadataContext}
 * 都是 ThreadLocal，到那时已经取不到值——历史上附件元数据就是这样丢的
 * （表现：上传文件后，聊天记录里文件信息不见了；同时 userId 只能靠 Redis 缓存兜底，
 * 而那个 key 只活 5 分钟，过期后聊天记录直接写不进库）。
 * <p>
 * 解法：在**请求线程**上把需要跨线程的数据装进 RunContext，然后显式传递下去，
 * 不再依赖任何 ThreadLocal。
 * <p>
 * 不可变对象，可安全跨线程传递。
 *
 * @param userId          当前用户 ID，用于聊天记录归属与越权过滤。不可为空。
 * @param sessionId       会话 ID（UUID），一次运行期间固定；新会话时由调用方生成
 * @param newSession      本次运行是否开启了一个新会话（决定是否推送 session_id 事件、是否异步生成标题）
 * @param messageMetadata 需要写入用户消息 attributes 的元数据（附件列表等）。可为空 Map。
 * @param runId           本次运行的 trace_id（产物归属用，见 docs/sql/011）。
 *                        <p>
 *                        它会被同时写进「本次运行落库的每一条历史消息」与「本次运行产出的每个产物」，
 *                        前端按 runId 相等把产物挂到产出它的那一轮。**必须持久化**，所以在这里显式传递
 *                        —— 流式回调线程上拿不到任何 ThreadLocal。
 *                        </p>
 * @param regenerateFromMessageId
 *                        「重新生成」时用户所在那条回答的 id（2026-10-06）。
 *                        <p>
 *                        <b>null = 普通发问</b>，行为与本字段出现前完全一致。非空时落库阶段要：
 *                        ①跳过用户提问（问题已问过，重复存会让模型看到「问了两遍」）；
 *                        ②把更早的现行回答标记为被本次替代（docs/sql/013）。
 *                        <p>
 *                        为什么必须在这里显式传递：这两个动作都发生在
 *                        <b>流式回调线程</b>（{@code ChatMemoryStore.updateMessages}），那里没有请求体。
 *                        </p>
 */
public record RunContext(Long userId,
                         String sessionId,
                         boolean newSession,
                         Map<String, Object> messageMetadata,
                         String runId,
                         String regenerateFromMessageId) {

    public RunContext {
        messageMetadata = messageMetadata == null ? Map.of() : Map.copyOf(messageMetadata);
    }

    /**
     * 普通发问的便捷构造（等价于「不重新生成」）。
     * <p>
     * 加它而不是让每个调用点多传一个 {@code null}：{@code regenerateFromMessageId}
     * 是个「大多数场景没有」的可选参数，让它出现在每个构造点只会稀释可读性，
     * 而且迟早有人会传错位置。
     */
    public RunContext(Long userId, String sessionId, boolean newSession,
                      Map<String, Object> messageMetadata, String runId) {
        this(userId, sessionId, newSession, messageMetadata, runId, null);
    }

    public boolean hasMessageMetadata() {
        return !messageMetadata.isEmpty();
    }

    /** 本次是否为「重新生成」 */
    public boolean isRegenerate() {
        return regenerateFromMessageId != null && !regenerateFromMessageId.isBlank();
    }
}
