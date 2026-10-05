package com.huzhijian.nexusagentweb.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行期「会话 → 用户」注册表。
 * <p>
 * <b>为什么需要它</b>：工具是在 LangChain4j 的<b>流式回调线程</b>上执行的，
 * 那不是处理 HTTP 请求的那个线程，{@link UserContextHolder}（ThreadLocal）在那里
 * 必然取不到值。这不是偶发问题，是架构上的必然 —— {@link RunContext} 的类注释
 * 已经把这件事写明了（附件元数据曾因此丢失过）。
 * <p>
 * <b>但工具拿不到 RunContext</b>：{@code ToolRegistry} 给出去的是 Spring 单例 bean，
 * 无法像 {@code PgChatMemoryStore#forRun(RunContext)} 那样为每次运行生成绑定实例。
 * <p>
 * <b>解法</b>：请求线程上把 sessionId → userId 登记进来（{@link #register}），
 * 工具侧用 LangChain4j 注入的 {@code @ToolMemoryId}（就是 sessionId）反查
 * （{@link #findUserId}）。参照项目里 {@code SandboxSessionRegistry} 的同款做法。
 * <p>
 * <b>安全说明</b>：查不到就返回 null，由调用方按「拒绝」处理 ——
 * 绝不能退化成"随便取一个用户"，那是跨用户数据泄露。
 * <p>
 * <b>局限</b>：只存在于内存，应用重启后丢失（重启后旧会话的工具调用会拿不到 userId
 * 而被拒绝）。若要跨重启，需要落库或延长注册表生命周期，属后续优化。
 * 当前 SSE 超时上限是 {@code nexus.agent.sse.timeout}（AgentProperties 默认 1800 秒），
 * 而清理发生在请求线程结束时，正常对话期间注册表一定还在。
 */
@Slf4j
@Component
public class RunUserRegistry {

    /** key = sessionId，value = 该次运行的 userId */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** 兜底的最长存活时间：超过它即便请求没结束也判定失效，避免内存无限增长 */
    private static final Duration MAX_TTL = Duration.ofMinutes(30);

    private record Entry(Long userId, Instant registeredAt) {
        boolean expired() {
            return Instant.now().isAfter(registeredAt.plus(MAX_TTL));
        }
    }

    /**
     * 登记一次运行。必须在**请求线程**上调用（那时 UserContextHolder 才有效）。
     *
     * @param sessionId 本次运行的会话 id
     * @param userId    当前登录用户
     */
    public void register(String sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            return;
        }
        entries.put(sessionId, new Entry(userId, Instant.now()));
    }

    /**
     * 按 sessionId 反查 userId。工具侧用 {@code @ToolMemoryId} 的值调用。
     *
     * @return 查不到（或已过期）返回 null，调用方必须按拒绝处理
     */
    public Long findUserId(Object sessionId) {
        if (sessionId == null) {
            return null;
        }
        String key = String.valueOf(sessionId);
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired()) {
            entries.remove(key);
            log.debug("运行期用户注册表已过期：sessionId={}", key);
            return null;
        }
        return entry.userId();
    }

    /**
     * 请求结束时清理，防止注册表无限增长。
     */
    public void unregister(String sessionId) {
        if (sessionId != null) {
            entries.remove(sessionId);
        }
    }

    /**
     * 当前登记的会话数，供监控与排查。
     */
    public int size() {
        return entries.size();
    }
}
