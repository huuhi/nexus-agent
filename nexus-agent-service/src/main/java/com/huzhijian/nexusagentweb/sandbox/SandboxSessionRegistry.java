package com.huzhijian.nexusagentweb.sandbox;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 沙盒会话注册表 —— 按会话复用沙盒，并负责主动回收。
 * <p>
 * 解决的问题：E2B 云沙盒是**按量计费**的。原实现每次工具调用都可能新建沙盒，
 * 且 Java 侧没有任何回收逻辑，只能等 E2B 自己超时（沙盒服务里设的是 600 秒）。
 * 结果就是同一段对话里反复创建沙盒，白花钱。
 * <p>
 * 现在的行为：
 * <ul>
 *   <li>{@link #acquire(String)} 同一会话只创建一次，后续复用</li>
 *   <li>{@link #release(String)} 会话结束时主动销毁</li>
 *   <li>后台任务定期清理**空闲超时**的沙盒（阈值见 {@code nexus.agent.sandbox.idle-timeout}）</li>
 *   <li>应用关闭时销毁全部沙盒（{@link PreDestroy}）</li>
 * </ul>
 * <p>
 * 局限：注册表只存在于内存，应用重启后无法回收重启前创建的沙盒
 * （那些由 E2B 侧 600 秒超时兜底）。要做到跨重启回收需要把 boxId 落库，属后续优化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxSessionRegistry {

    private final SandboxClient sandboxClient;
    private final AgentProperties agentProperties;

    /** key = 会话标识（当前用会话 ID）。value = 该会话的沙盒信息 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 一个会话的沙盒记录 */
    private static final class Session {
        final String boxId;
        final Instant createdAt;
        volatile Instant lastUsedAt;

        Session(String boxId, Instant now) {
            this.boxId = boxId;
            this.createdAt = now;
            this.lastUsedAt = now;
        }
    }

    /**
     * 取会话的沙盒：已有则复用，没有则创建。
     *
     * @param sessionKey 会话标识
     * @return 沙盒创建/复用结果（形状与沙盒服务返回一致，另加 reused 标记）
     */
    public Map<String, Object> acquire(String sessionKey) {
        if (sessionKey == null || !agentProperties.getSandbox().isReusePerSession()) {
            // 没有会话标识（如 @ToolMemoryId 未注入）或关闭了复用：直接新建，不进注册表。
            // 注意不能拿 null 去查 ConcurrentHashMap —— 它不接受 null 键，会抛 NPE。
            return sandboxClient.createBox();
        }
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.lastUsedAt = Instant.now();
            log.debug("会话 {} 复用沙盒 {}", sessionKey, session.boxId);
            return Map.of(
                    "message", "已复用当前会话的沙盒",
                    "box_id", session.boxId,
                    "reused", true
            );
        }
        return sandboxClient.createBox();
    }

    /**
     * 记录新创建的沙盒。
     */
    public void register(String sessionKey, String boxId) {
        if (sessionKey == null || boxId == null || boxId.isBlank()) {
            return;
        }
        if (!agentProperties.getSandbox().isReusePerSession()) {
            return;
        }
        sessions.put(sessionKey, new Session(boxId, Instant.now()));
        log.debug("会话 {} 注册沙盒 {}", sessionKey, boxId);
    }

    /**
     * 当前会话的沙盒 ID；没有则返回 null。
     */
    public String boxIdOf(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        Session session = sessions.get(sessionKey);
        return session == null ? null : session.boxId;
    }

    /**
     * 刷新使用时间，避免正在使用的沙盒被回收任务误删。
     */
    public void touch(String sessionKey) {
        if (sessionKey == null) {
            return;
        }
        Session session = sessions.get(sessionKey);
        if (session != null) {
            session.lastUsedAt = Instant.now();
        }
    }

    /**
     * 主动销毁会话的沙盒并移除记录。
     */
    public Map<String, Object> release(String sessionKey) {
        Session session = sessionKey == null ? null : sessions.remove(sessionKey);
        if (session == null) {
            return Map.of("success", true, "message", "当前会话没有需要销毁的沙盒");
        }
        return destroy(session.boxId, "会话主动释放");
    }

    /**
     * 作废会话的沙盒记录，但**不**尝试销毁。
     * <p>
     * 用于「沙盒已经不存在」的场景（E2B 会自动暂停空闲沙盒，此时再 DELETE 会报
     * Paused sandbox not found）。作废后，模型下次调用 create_box 会重新创建，
     * 实现自愈。
     */
    public void invalidate(String sessionKey) {
        if (sessionKey == null) {
            return;
        }
        Session removed = sessions.remove(sessionKey);
        if (removed != null) {
            log.info("会话 {} 的沙盒 {} 已失效，作废记录（下次 create_box 会重建）",
                    sessionKey, removed.boxId);
        }
    }

    /**
     * 定期回收空闲超时的沙盒。
     * <p>
     * 间隔固定 60 秒（可用 {@code nexus.agent.sandbox.sweep-interval} 覆盖，单位毫秒或 ISO-8601）；
     * 空闲阈值见 {@code nexus.agent.sandbox.idle-timeout}，默认 8 分钟 —— 必须小于
     * E2B 侧的超时（沙盒服务里是 600 秒），否则还没轮到我们回收就已经被 E2B 收走了。
     */
    @Scheduled(
            fixedDelayString = "${nexus.agent.sandbox.sweep-interval:60000}",
            initialDelayString = "${nexus.agent.sandbox.sweep-interval:60000}")
    public void sweepIdleSandboxes() {
        Duration idleTimeout = agentProperties.getSandbox().getIdleTimeout();
        Instant deadline = Instant.now().minus(idleTimeout);
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.lastUsedAt.isBefore(deadline)) {
                if (sessions.remove(entry.getKey(), session)) {
                    log.info("回收空闲沙盒：会话={} boxId={} 空闲超过 {}",
                            entry.getKey(), session.boxId, idleTimeout);
                    destroy(session.boxId, "空闲超时回收");
                }
            }
        }
    }

    /**
     * 应用关闭时统一销毁，避免残留沙盒继续计费。
     */
    @PreDestroy
    public void destroyAll() {
        if (sessions.isEmpty()) {
            return;
        }
        log.info("应用关闭，销毁 {} 个沙盒", sessions.size());
        sessions.forEach((key, session) -> destroy(session.boxId, "应用关闭"));
        sessions.clear();
    }

    /** 当前活跃沙盒数量（供排查/监控） */
    public int activeCount() {
        return sessions.size();
    }

    private Map<String, Object> destroy(String boxId, String reason) {
        try {
            Map<String, Object> result = sandboxClient.deleteBox(boxId);
            log.debug("销毁沙盒 {}（{}）：{}", boxId, reason, result);
            return result == null ? Map.of("success", true) : result;
        } catch (Exception e) {
            // 销毁失败不影响主流程：沙盒最终会被 E2B 侧超时回收
            log.warn("销毁沙盒 {} 失败（{}）：{}", boxId, reason, e.getMessage());
            return Map.of("success", false, "message", "销毁沙盒失败：" + e.getMessage());
        }
    }
}
