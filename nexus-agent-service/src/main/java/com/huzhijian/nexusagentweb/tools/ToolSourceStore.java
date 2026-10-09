package com.huzhijian.nexusagentweb.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 工具调用的<b>结构化结果</b>暂存（2026-10-08 新增）。给 SSE 的"来源卡片"用。
 *
 * <h2>为什么会需要这么个东西</h2>
 * 想让前端显示「已搜索 N 个来源 + 来源卡片 + 正文里的 [1] 角标」（Perplexity 那种），
 * 后端就必须把<b>结构化的来源数组</b>发过去 —— 只有一串给模型读的纯文本是不够的。
 * <p>
 * 但这里有个卡死的地方：
 * <ol>
 *   <li>工具由 LangChain4j <b>自动执行</b>（{@code TokenStream} 的 {@code onToolExecuted}），
 *       结果到达业务层时<b>已经被 {@code String} 化了</b>——拿不到原始的 {@code List<Result>}；</li>
 *   <li>又不能直接把 VO 揉进返回值 —— 那串文本要进<b>模型上下文</b>，多塞一份 JSON 就是白烧 token，
 *       而且会污染历史消息。</li>
 * </ol>
 * 于是只能留一条**带外信道（side channel）**：工具把结构化结果存在这里，
 * SSE 转换器发 {@code tool_execution_result} 事件时按 <b>sessionId + 工具名</b> 取走。
 *
 * <h2>为什么用「sessionId + 工具名」做键、按 FIFO 取</h2>
 * 两边各自握着同一个 sessionId（工具的 {@code @ToolMemoryId} 就是它，
 * SSE 侧也在自己手里 —— 见 {@code RunUserRegistry} 的约定），所以不需要额外传什么。
 * 同一个会话里同一个工具可能被连续调用多次（"先搜一遍，换个词再搜"），
 * 结果的产生顺序与工具名逐一对应 —— 因此按 FIFO 消费是对的。
 * <p>
 * ⚠️ <b>已知边界</b>：若同一次 response 里<b>并行</b>调用了同一个工具两遍，
 * 配对顺序不保证（ LangChain4j 不承诺并行工具结果的回调次序）。
 * 后果只是「两次搜索的来源可能串位」，不会崩，也不影响模型侧的任何东西 ——
 * 属于可接受误差，真要精确可以把 参数指纹 也并入键里（未来要做再说）。
 *
 * <h2>内存安全</h2>
 * 每个键的队列有上限，且条目有 TTL —— SSEE 侧忘了取（比如工具调用后连接已断开）
 * 也不会无限增长。清理是「顺手做」的（写入与读取时扫描），不起后台线程。
 */
@Slf4j
@Component
public class ToolSourceStore {

    /** 单个 (会话, 工具) 最多积压多少次结果；超了丢最老的（那是没人要的死数据） */
    private static final int MAX_PER_KEY = 8;

    /** 条目最长存活时间。SSE 单次请求远短于此，纯兜底 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Entry>> store = new ConcurrentHashMap<>();

    private record Entry(List<Map<String, Object>> sources, Instant createdAt) {
    }

    /**
     * 记录一次工具调用的结构化结果。
     *
     * @param sessionId 会话 id（工具的 {@code @ToolMemoryId} 就是它）
     * @param toolName  工具名，如 {@code web_search}
     * @param sources   结构化结果；null 或 空 时不记录（省一次队列占位）
     */
    public void record(Object sessionId, String toolName, List<Map<String, Object>> sources) {
        if (sessionId == null || toolName == null || sources == null || sources.isEmpty()) {
            return;
        }
        String key = keyOf(sessionId, toolName);
        ConcurrentLinkedQueue<Entry> queue = store.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        queue.add(new Entry(List.copyOf(sources), Instant.now()));
        while (queue.size() > MAX_PER_KEY) {
            queue.poll();
        }
        sweepExpired();
    }

    /**
     * 取走一次工具调用的结构化结果（取完即删，不会重复下发）。
     *
     * @return 没有积压（或已过期）返回 {@code null}，调用方按"没有来源"处理
     */
    public List<Map<String, Object>> take(Object sessionId, String toolName) {
        if (sessionId == null || toolName == null) {
            return null;
        }
        Entry entry = store.getOrDefault(keyOf(sessionId, toolName), new ConcurrentLinkedQueue<>()).poll();
        if (entry == null) {
            return null;
        }
        if (entry.createdAt().isBefore(Instant.now().minus(TTL))) {
            log.debug("工具来源已过期，丢弃：session={} tool={}", sessionId, toolName);
            return null;
        }
        return entry.sources();
    }

    /** 当前积压条数（仅用于测试与排障） */
    public int pendingCount(Object sessionId, String toolName) {
        ConcurrentLinkedQueue<Entry> queue = store.get(keyOf(sessionId, toolName));
        return queue == null ? 0 : queue.size();
    }

    private static String keyOf(Object sessionId, String toolName) {
        return String.valueOf(sessionId) + "|" + toolName;
    }

    /**
     * 清掉过期条目与空队列。
     * <p>
     * 刻意顺风清理而不是起定时任务：本对象的生命周期就是"一次会话内的工具结果中转"，
     * 量极小，再来一个后台线程反而多一处泄漏点（参见 SSE 心跳池那次）。
     */
    private void sweepExpired() {
        Instant deadline = Instant.now().minus(TTL);
        List<String> emptyKeys = new ArrayList<>();
        for (Map.Entry<String, ConcurrentLinkedQueue<Entry>> e : store.entrySet()) {
            ConcurrentLinkedQueue<Entry> queue = e.getValue();
            queue.removeIf(entry -> entry.createdAt().isBefore(deadline));
            if (queue.isEmpty()) {
                emptyKeys.add(e.getKey());
            }
        }
        for (String k : emptyKeys) {
            store.remove(k, new ConcurrentLinkedQueue<>());
        }
    }
}
