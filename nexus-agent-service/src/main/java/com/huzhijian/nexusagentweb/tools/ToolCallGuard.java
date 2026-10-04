package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 工具调用治理 —— 拦截「同一会话内短时间重复的完全相同调用」。
 * <p>
 * <b>为什么需要</b>：模型陷入循环时会反复调用同一个工具、传完全一样的参数
 * （典型：`execute_cmd` 同一条命令连续跑、`create_box` 反复建），
 * 每一次都真的花时间/花 token，E2B 沙盒还按量计费。提示词里那句
 * 「工具调用失败尝试最多两次」是**软约束**，模型不一定听；这里做硬拦截。
 * <p>
 * <b>判定规则</b>：以 (会话ID, 工具名, 参数指纹) 为键，在 {@code duplicate-window}
 * 窗口内累计调用次数；次数达到 {@code duplicate-threshold} 后，后续相同调用被拦截，
 * 返回结构化结果（形状与 {@code SafeExecuteToolHandler} 的失败结果一致，
 * 便于模型统一理解）并附「不要重复调用」的自纠提示。
 * <p>
 * <b>设计取舍</b>：
 * <ul>
 *   <li>用**滑动窗口内的计数**而不是「只记上一次」：这样模型连续刷同一条命令时，
 *       窗口内会一直处于被拦状态，不会因为间隔刚好跨过判定而漏拦。</li>
 *   <li>拦截**不抛异常**、不改工具签名语义 —— 工具方法第一行调用
 *       {@link #intercept}，非 null 就直接 return 给模型，实现成本极低。</li>
 *   <li>只拦「完全相同的调用」；参数只要有一处不同就正常放行，
 *       避免误伤「换参数重试」这类正当行为。</li>
 *   <li>键里带会话 ID：不同会话互不影响（用户 A 的重复不会拦到用户 B）。</li>
 * </ul>
 * <p>
 * <b>新增工具时请接入</b>（见 {@code AGENTS.md §6.4}）：在 {@code @Tool} 方法第一行调用
 * {@link #intercept}，把「能区分是不是同一次调用」的参数传进来即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCallGuard {

    /** 拦截结果的错误码，与 SafeExecuteToolHandler 的错误码风格保持一致 */
    public static final String CODE_DUPLICATE_CALL = "DUPLICATE_CALL";

    private static final String HINT =
            "这是重复调用：相同参数在短时间内已执行过，结果与上次相同，本次未真正执行。"
                    + "不要重复调用；如需继续，请改用不同的参数，或基于上一次的结果继续推理；"
                    + "若确实需要重复执行，请向用户说明原因并等待确认。";

    /** 会话标识缺失时的兜底键 —— 宁可少拦（不同匿名会话串到一起），也不要 NPE 或放行所有校验 */
    private static final String ANONYMOUS_SESSION = "anonymous";

    private final AgentProperties agentProperties;

    /** key = session|tool|argsHash → 窗口内的调用时间戳（毫秒） */
    private final ConcurrentHashMap<String, Deque<Long>> recentCalls = new ConcurrentHashMap<>();

    /**
     * 检查并登记一次调用。
     *
     * @param sessionId     会话标识（工具的 {@code @ToolMemoryId} 参数）；可为 null
     * @param toolName      工具名（{@code @Tool(name=...)} 里的名字）
     * @param argsFingerprint 参数指纹：把能区分「是不是同一次调用」的参数拼起来即可，
     *                        只传关键参数（如 cmd / path）就够，不必传全部
     * @return null 表示放行；非 null 表示应当拦截，直接把该 Map 返回给模型
     */
    public Map<String, Object> intercept(Object sessionId, String toolName, String argsFingerprint) {
        AgentProperties.Tools config = agentProperties.getTools();
        int threshold = config.getDuplicateThreshold();
        Duration window = config.getDuplicateWindow();
        if (threshold <= 0 || window == null || window.isZero() || window.isNegative()) {
            return null; // 治理关闭
        }

        long now = System.currentTimeMillis();
        String key = key(sessionId, toolName, argsFingerprint);
        Deque<Long> timestamps = recentCalls.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            long cutoff = now - window.toMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= threshold) {
                // 被拦时不登记新时间戳：窗口内会一直保持拦截，直到最早的调用滑出窗口
                log.warn("拦截重复工具调用：会话={} 工具={} 窗口内已调用 {} 次（阈值 {}）",
                        sessionId, toolName, timestamps.size(), threshold);
                return blockedPayload(sessionId, toolName, timestamps.size(), window);
            }
            timestamps.addLast(now);
        }
        return null;
    }

    /**
     * 文本版拦截：给**返回 String** 的工具用（LogTool / MemoryTool / LexiangRagTool）。
     *
     * @return null 表示放行；非 null 为紧凑 JSON 文本，直接 return 给模型即可
     */
    public String interceptText(Object sessionId, String toolName, String argsFingerprint) {
        Map<String, Object> blocked = intercept(sessionId, toolName, argsFingerprint);
        return blocked == null ? null : JSONUtil.toJsonStr(blocked);
    }

    /**
     * 把若干参数拼成指纹字符串，供工具方法调用：
     * <pre>
     * Map&lt;String, Object&gt; blocked = toolCallGuard.intercept(memoryId, "execute_cmd", ToolCallGuard.fingerprint(cmd));
     * if (blocked != null) { return blocked; }
     * </pre>
     * 实现要点：null 安全；用 {@code \u0001} 作分隔符，避免 {@code ("ab","c")} 与 {@code ("a","bc")}
     * 拼出同一个字符串造成**误判为重复**。
     */
    public static String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part == null ? "\u0000" : part).append('\u0001');
        }
        return sb.toString();
    }

    /**
     * 拦截结果：形状与 {@code SafeExecuteToolHandler} 的失败结果一致，模型无需学两套。
     */
    private Map<String, Object> blockedPayload(Object sessionId, String toolName, int count, Duration window) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", CODE_DUPLICATE_CALL);
        payload.put("message", "重复调用被拦截：工具「" + toolName + "」在 " + window.toSeconds()
                + " 秒内已用完全相同的参数调用过 " + count + " 次");
        payload.put("hint", HINT);
        return payload;
    }

    private String key(Object sessionId, String toolName, String argsFingerprint) {
        String session = sessionId == null || String.valueOf(sessionId).isBlank()
                ? ANONYMOUS_SESSION
                : String.valueOf(sessionId);
        // 参数可能很长（如整段代码），做摘要避免键过长占用内存
        return session + '|' + toolName + '|' + digest(argsFingerprint);
    }

    /**
     * 计算参数摘要。用 SHA-256 而不是 {@code String.hashCode()}：
     * 后者 32 位、碰撞概率在同会话高频调用下不可忽略，碰撞会造成**误拦**。
     */
    private static String digest(String args) {
        if (args == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(args.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) { // 取前 16 字节足够，键更短
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256；真出现也只能降级，不应让工具调用失败
            log.warn("SHA-256 不可用，降级为 hashCode 作为参数指纹：{}", e.getMessage());
            return Integer.toHexString(args.hashCode());
        }
    }

    /**
     * 定期清理已过期的键，避免长时间运行下 Map 无限增长。
     * <p>
     * 判定「过期」用最大窗口的保守值：条目里最早的时间戳已滑出窗口即可删。
     */
    @Scheduled(fixedDelayString = "${nexus.agent.tools.guard-sweep-interval:600000}")
    public void sweep() {
        Duration window = agentProperties.getTools().getDuplicateWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            recentCalls.clear();
            return;
        }
        long cutoff = System.currentTimeMillis() - window.toMillis();
        int removed = 0;
        for (Map.Entry<String, Deque<Long>> entry : recentCalls.entrySet()) {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                    timestamps.pollFirst();
                }
                if (timestamps.isEmpty()) {
                    recentCalls.remove(entry.getKey());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("工具调用治理：清理 {} 个过期条目，剩 {} 个", removed, recentCalls.size());
        }
    }

    /**
     * 当前登记的键数量。
     * <p>
     * 公开是为了让测试能断言「过期条目被清理」，顺带也可作为排障用的观测点。
     */
    public int trackedKeys() {
        return recentCalls.size();
    }
}
