package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.utils.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手阶段的鉴权。
 * <p>
 * <b>为什么必须有这个类</b>：{@code @ServerEndpoint} 注册的是 <b>JSR-356 原生端点</b>，
 * 它<b>不经过 Spring MVC 的 DispatcherServlet</b>，因此
 * {@link com.huzhijian.nexusagentweb.interceptor.LoginCheckInterceptor}
 * 对它 <b>100% 无效</b>。修复前 {@code WebSocketService.onOpen} 是这样的：
 * <pre>
 *   public void onOpen(Session session, @PathParam("userId") String userId) {
 *       CLIENTS.put(userId, session);   // 直接信任 URL 里的 userId
 *   }
 * </pre>
 * 而 userId 是自增整数 → <b>任何人都能枚举订阅别人的消息推送</b>；
 * 又因为 WebSocket <b>不受浏览器同源策略约束</b>，任意网站发起的连接都能建立。
 *
 * <p>
 * <b>token 从哪来</b>：浏览器的 {@code new WebSocket(url)} <b>不能自定义请求头</b>，
 * 所以不能像 REST 那样靠 {@code token} 头。这里两种都支持，按优先级：
 * <ol>
 *   <li><b>子协议</b>（浏览器唯一可用）：{@code new WebSocket(url, ["nexus-token", token])} ——
 *       浏览器会把它发成 {@code Sec-WebSocket-Protocol: nexus-token, <jwt>}，
 *       不进 URL 日志。⚠️ 注意 token 是<b>独立的第二项子协议</b>，
 *       不是拼在 {@code nexus-token} 后面（那是少数手写客户端的写法，本类也兼容）</li>
 *   <li><b>查询参数</b>：{@code /api/ws/{userId}?token=xxx} ——
 *       兼容性最好，但 token 会进 nginx access log，<b>需自行确认日志脱敏</b></li>
 * </ol>
 * 原生客户端（如 {@code websocat}）可以直接用请求头 {@code token: xxx}。
 *
 * <p>
 * <b>关键：token 里的 userId 必须与 URL 里的 userId 一致</b>。
 * 只校验「有没有有效 token」是不够的 —— 那等于「任何登录用户都能订阅任何人」，
 * 枚举 userId 依然畅通。这里做相等性比对，把枚举彻底堵死。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Slf4j
@Component
// 与 LoginCheckInterceptor 用同一个开关：本地调试关掉鉴权时，WS 也不拦
@ConditionalOnProperty(
        name = "nexus.agent.security.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    /** 子协议名：客户端用 ["nexus-token", "<jwt>"] 传 token */
    public static final String SUBPROTOCOL_TOKEN = "nexus-token";

    /** 子协议请求头的标准名字（抽成常量，避免在两处硬编码字符串） */
    private static final String SUBPROTOCOL_HEADER = "Sec-WebSocket-Protocol";

    /** 查询参数兜底：?token=xxx */
    private static final String QUERY_PARAM_TOKEN = "token";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 路径形如 /api/ws/{userId}，取最后一段作为"声称的"用户 id
        String path = request.getURI().getPath();
        String claimedUserId = extractUserIdFromPath(path);

        String token = extractToken(request);

        if (token == null || token.isBlank()) {
            log.warn("WebSocket 握手被拒：缺少 token，path={}", path);
            return false;
        }

        Long realUserId;
        try {
            realUserId = JwtUtil.getIdFromToken(token, "user_id");
        } catch (Exception e) {
            log.warn("WebSocket 握手被拒：token 解析失败（{}），path={}", e.getMessage(), path);
            return false;
        }

        // ⚠️ 必须判 null：getIdFromToken 在 token 过期/签名不符时返回 null 而不抛异常
        if (realUserId == null) {
            log.warn("WebSocket 握手被拒：token 无效（解析不出 user_id），path={}", path);
            return false;
        }

        // 核心：token 的身份必须与 URL 里声称的一致，否则仍可枚举他人
        if (claimedUserId != null && !claimedUserId.equals(String.valueOf(realUserId))) {
            log.warn("WebSocket 握手被拒：token 身份与 URL 中的 userId 不一致（token={}, url={}）",
                    realUserId, claimedUserId);
            return false;
        }

        // 交给 onOpen 用这个**已验证**的身份，别再让它自己信任 URL
        attributes.put("userId", realUserId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需清理：握手线程的 ThreadLocal 由 WebSocketService 自己管
    }

    /** 从 /api/ws/{userId} 里取出 userId；取不到返回 null（此时只校验 token 有效性） */
    private String extractUserIdFromPath(String path) {
        if (path == null) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        String seg = path.substring(idx + 1);
        // 去掉可能存在的查询串残留与空白
        seg = seg.trim();
        return seg.isEmpty() ? null : seg;
    }

    /**
     * 按优先级取 token：请求头 → 子协议 → 查询参数。
     * <p>
     * 请求头放在最前，是为了让 <b>原生客户端</b>（websocat / Java / Python）
     * 可以用与 REST 一致的 {@code token} 头，不必改造成查询参数。
     */
    private String extractToken(ServerHttpRequest request) {
        // 1) 请求头（原生客户端）
        String header = request.getHeaders().getFirst("token");
        if (header != null && !header.isBlank()) {
            return header;
        }

        // 2) 子协议：浏览器 new WebSocket(url, ["nexus-token", token]) 发来的是
        //    Sec-WebSocket-Protocol: nexus-token, eyJhbGciOi...
        //    ⚠️ token 是**独立的第二项子协议**，不是"第一项后面粘上去的"。
        //    这里两种形态都认：
        //      · 分隔符形式「nexus-token, <jwt>」—— 浏览器与大部分客户端
        //      · 拼接形式「nexus-token<jwt>」—— 少数手写客户端（容错，保留）
        //    写死只认拼接形式会让浏览器**根本连不上**，而这恰恰是浏览器唯一可用的通道。
        String subprotocols = request.getHeaders().getFirst(SUBPROTOCOL_HEADER);
        if (subprotocols != null && !subprotocols.isBlank()) {
            String[] parts = subprotocols.split(",");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.equals(SUBPROTOCOL_TOKEN)) {
                    // 形态一：下一项就是 token
                    if (i + 1 < parts.length) {
                        String next = parts[i + 1].trim();
                        if (!next.isEmpty()) {
                            return next;
                        }
                    }
                } else if (p.startsWith(SUBPROTOCOL_TOKEN)) {
                    // 形态二：同一项内拼接
                    String rest = p.substring(SUBPROTOCOL_TOKEN.length()).trim();
                    if (!rest.isEmpty()) {
                        return rest;
                    }
                }
            }
        }

        // 3) 查询参数兜底
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String param = servletRequest.getServletRequest().getParameter(QUERY_PARAM_TOKEN);
            if (param != null && !param.isBlank()) {
                return param;
            }
        }
        return null;
    }
}
