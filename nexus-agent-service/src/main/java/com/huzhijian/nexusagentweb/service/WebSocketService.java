package com.huzhijian.nexusagentweb.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 推送端点（路径 {@code /api/ws/{userId}}）。
 * <p>
 * <b>⚠️ 鉴权说明（2026-10-04 修复 P0）</b>
 * <p>
 * 修复前本类用 {@code @ServerEndpoint}，走的是 <b>JSR-356 原生端点路径</b>，
 * <b>完全不经过 Spring MVC 的 DispatcherServlet</b>，所以
 * {@code LoginCheckInterceptor} 对它 100% 无效。而当时的 {@code onOpen} 直接信任
 * URL 里的 userId：
 * <pre>
 *   public void onOpen(Session session, @PathParam("userId") String userId) {
 *       CLIENTS.put(userId, session);   // 直接信任 URL 里的 userId
 *   }
 * </pre>
 * userId 是自增整数 → <b>任何人都能枚举订阅别人的消息推送</b>；又因为 WebSocket
 * <b>不受浏览器同源策略约束</b>，任意网站发起的连接都能建立。
 * <p>
 * 现在改为 {@link org.springframework.web.socket.config.annotation.EnableWebSocket} +
 * {@code WebSocketConfigurer}（见 {@code WebSocketConfiguration}）托管端点，
 * 由 {@link com.huzhijian.nexusagentweb.config.WebSocketAuthInterceptor} 在
 * <b>握手阶段</b>校验 token，把<b>已验证的 userId</b> 放进 session 属性；
 * 本类<b>只读这个属性，绝不再解析 URL</b>。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Slf4j
@Component
public class WebSocketService extends TextWebSocketHandler {

    /** userId(String) → 连接 */
    private static final Map<String, WebSocketSession> CLIENTS = new ConcurrentHashMap<>();

    /** 握手拦截器放进去的、已通过 token 校验的用户 id（Long） */
    private static final String ATTR_VERIFIED_USER_ID = "userId";

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 优先用握手阶段校验过的身份；取不到就断开
        // （绝不回退到 URL 里的 userId —— 那正是被修掉的漏洞）
        Object verified = session.getAttributes().get(ATTR_VERIFIED_USER_ID);
        if (verified == null) {
            log.warn("客户端连接被拒：握手阶段没有通过鉴权，sessionId={}", session.getId());
            closeQuietly(session);
            return;
        }
        String key = String.valueOf(verified);

        // ⚠️ 同一用户重复连接（换浏览器 / 刷新 / 网络重连）时，
        // 原实现是 put 无条件覆盖 —— 攻击者只要用同一 userId 连上就能把受害者挤下线（DoS），
        // 且自己接管了推送。这里改为：先记下旧的再关掉它，最后放新的。
        WebSocketSession old = CLIENTS.put(key, session);
        if (old != null && old != session && old.isOpen()) {
            closeQuietly(old);
            log.debug("替换用户 {} 的旧 WebSocket 连接", key);
        }
        log.info("客户端连接: {}", key);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object verified = session.getAttributes().get(ATTR_VERIFIED_USER_ID);
        // ⚠️ 必须比对 session 是当前那个再删，否则「刚被踢掉的旧连接」触发关闭回调时
        // 会把刚建立的新连接一起删掉（竞态，表现为「连上就断」）
        if (verified != null) {
            CLIENTS.remove(String.valueOf(verified), session);
        }
        log.info("客户端断开: {}（{}）", verified == null ? "未验证身份" : verified, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误，sessionId={}", session.getId(), exception);
    }

    /**
     * 本端只做「单向推送」，不处理客户端发来的文本。
     * <p>
     * 保留空实现（而非不写）是为了让日志里能看出「客户端确实发了东西」，
     * 便于排查异常客户端；同时明确<b>不</b>把内容打进日志，防止刷屏与泄漏。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("收到客户端消息，sessionId={}，长度={}", session.getId(),
                message.getPayload() == null ? 0 : message.getPayload().length());
    }

    /** 关闭失败不抛出：只在清理路径上调用，抛出去只会掩盖真正的问题 */
    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (Exception e) {
            log.debug("关闭 WebSocket 连接失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 发送消息给指定用户。
     * <p>
     * ⚠️ {@code WebSocketSession} <b>不是线程安全的</b>：并发写会让帧交错、产生
     * 非法报文甚至连接异常。JSR-356 的 {@code getBasicRemote()} 阻塞等待，
     * 等于自带串行；换成 Spring 的 {@code sendMessage} 后这个保证没了，
     * 所以这里<b>显式 synchronized 到 session 上</b>，与原行为对齐。
     *
     * @param userId 目标用户 id（字符串形式，与 URL 段一致）
     * @param message 文本内容
     */
    public void sendToClient(String userId, String message) {
        WebSocketSession session = CLIENTS.get(userId);
        if (session == null || !session.isOpen()) {
            log.warn("用户 {} 的WebSocket连接不存在或已关闭", userId);
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(message));
                log.debug("发送消息给用户 {}: {}", userId, message);
            } catch (IOException | IllegalStateException e) {
                log.error("发送消息失败", e);
            }
        }
    }

    /**
     * 广播消息（保留原有功能，目前无调用方）。
     */
    public void sendToAllClient(String message) {
        CLIENTS.forEach((userId, session) -> {
            if (!session.isOpen()) {
                return;
            }
            synchronized (session) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IOException | IllegalStateException e) {
                    log.error("广播消息失败", e);
                }
            }
        });
    }
}
