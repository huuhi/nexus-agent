package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.WebSocketAuthInterceptor;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link WebSocketAuthInterceptor} 的纯单测 —— WebSocket 零鉴权（P0）的回归网。
 * <p>
 * <b>对应的漏洞（P0）</b>：端点原来用 {@code @ServerEndpoint}（JSR-356 原生端点），
 * <b>完全不经过 Spring MVC 的 DispatcherServlet</b>，所以 {@code LoginCheckInterceptor} 对它 100% 无效；
 * 而 {@code onOpen} 又直接信任 URL 里的 userId。于是<b>任何人枚举 userId 就能订阅别人的消息推送</b>，
 * 且 WebSocket 不受浏览器同源策略约束，任意网站发起的连接都能建立。
 * <p>
 * <b>本测试锁死两条关键性质</b>：
 * <ol>
 *   <li>没有 token 一律拒（不能"URL 里有 userId 就算数"）</li>
 *   <li><b>token 身份必须与 URL 里的 userId 一致</b> —— 只校验"有没有有效 token"是不够的，
 *       那等于"任何登录用户都能订阅任何人"，枚举照样畅通</li>
 * </ol>
 * <p>
 * <b>为什么用 {@link MockHttpServletRequest} 而不是 MockServerHttpRequest</b>：
 * spring-test 只提供 <b>reactive</b> 版（{@code ...server.reactive.MockServerHttpRequest}），
 * 它实现的是 {@code org.springframework.http.server.reactive.ServerHttpRequest}，
 * 与本拦截器接收的 Servlet 版不是同一个类型。用 Servlet 版反而更贴近真实运行时
 * （Tomcat 交过来的就是 {@link ServletServerHttpRequest}），
 * 顺带让"从查询参数取 token"这条分支也能被覆盖到。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@DisplayName("WebSocketAuthInterceptor —— 握手阶段必须校验 token 且身份要与 URL 一致")
class WebSocketAuthInterceptorTest {

    private static final long TTL = 60_000L;

    private static final WebSocketAuthInterceptor INTERCEPTOR = new WebSocketAuthInterceptor();
    private static final WebSocketHandler HANDLER = mock(WebSocketHandler.class);

    @BeforeAll
    static void fixJwtSecret() {
        // 固定密钥：否则 JwtUtil 会随机生成一个，同一份 token 在两次调用间就失效了
        JwtUtil.setConfiguredSecret("unit-test-only-jwt-secret-0123456789abcdef");
    }

    @AfterAll
    static void clearJwtSecret() {
        // 别把测试密钥留给其它测试类
        JwtUtil.setConfiguredSecret(null);
    }

    private static String tokenFor(long userId) {
        return JwtUtil.createJWT(TTL, Map.of("user_id", userId, "username", "u" + userId));
    }

    /** 造一个带 header 的握手请求 */
    private static ServerHttpRequest req(String path, String headerName, String headerValue) {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRequestURI(path);
        if (headerName != null) {
            servlet.addHeader(headerName, headerValue);
        }
        return new ServletServerHttpRequest(servlet);
    }

    /** 造一个带查询参数的握手请求 */
    private static ServerHttpRequest reqWithQuery(String path, String param, String value) {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRequestURI(path);
        servlet.setParameter(param, value);
        return new ServletServerHttpRequest(servlet);
    }

    private static boolean handshake(ServerHttpRequest request, Map<String, Object> attrs) {
        return INTERCEPTOR.beforeHandshake(request, mock(ServerHttpResponse.class), HANDLER, attrs);
    }

    private static Map<String, Object> attrs() {
        return new HashMap<>();
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("token 与 URL 一致：放行，并把已验证的 userId 放进 session 属性")
    void allowsMatchingIdentity() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(req("/api/ws/1001", "token", tokenFor(1001)), attrs);

        assertTrue(ok, "身份一致就该放行");
        // 关键：onOpen 读的是这个属性，URL 里的 userId 只用来做相等性比对
        assertEquals(1001L, attrs.get("userId"));
    }

    @Test
    @DisplayName("浏览器首选路径：子协议 Sec-WebSocket-Protocol: nexus-token,<jwt>")
    void allowsSubprotocolToken() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(
                req("/api/ws/1001", "Sec-WebSocket-Protocol", "nexus-token, " + tokenFor(1001)), attrs);

        assertTrue(ok, "浏览器不能自定义请求头，子协议是它唯一可用的通道，必须支持");
        assertEquals(1001L, attrs.get("userId"));
    }

    @Test
    @DisplayName("少数手写客户端的拼接形式 nexus-token<jwt> 也要认")
    void allowsConcatenatedSubprotocolToken() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(
                req("/api/ws/1001", "Sec-WebSocket-Protocol", "nexus-token" + tokenFor(1001)), attrs);

        assertTrue(ok);
        assertEquals(1001L, attrs.get("userId"));
    }

    @Test
    @DisplayName("子协议里带了别的协议名：也要能定位到 token（不能假设 nexus-token 排第一）")
    void allowsSubprotocolTokenNotFirst() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(
                req("/api/ws/1001", "Sec-WebSocket-Protocol", "chat, nexus-token, " + tokenFor(1001)), attrs);

        assertTrue(ok);
        assertEquals(1001L, attrs.get("userId"));
    }

    @Test
    @DisplayName("查询参数兜底：?token=xxx")
    void allowsQueryParamToken() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(reqWithQuery("/api/ws/1001", "token", tokenFor(1001)), attrs);

        assertTrue(ok, "查询参数是浏览器之外的兼容通道");
        assertEquals(1001L, attrs.get("userId"));
    }

    @Test
    @DisplayName("请求头优先于查询参数（原生客户端两者都传时以头为准）")
    void headerWinsOverQueryParam() {
        Map<String, Object> attrs = attrs();
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.setRequestURI("/api/ws/1001");
        servlet.addHeader("token", tokenFor(1001));
        // 查询参数里放一个属于别人的 token
        servlet.setParameter("token", tokenFor(2002));

        boolean ok = handshake(new ServletServerHttpRequest(servlet), attrs);

        assertTrue(ok);
        assertEquals(1001L, attrs.get("userId"), "必须用请求头里那个 token 的身份，不是查询参数里的");
    }

    // ---------------------------------------------------------------- 拒绝路径

    @Test
    @DisplayName("🔴 核心：token 有效但 URL 是别人的 userId → 拒绝（否则等于任何登录用户都能订阅任何人）")
    void rejectsIdentityMismatch() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(req("/api/ws/1002", "token", tokenFor(1001)), attrs);

        assertFalse(ok, "拿自己的 token 去连别人的通道，必须拒");
        assertNull(attrs.get("userId"), "被拒时不能留下任何身份信息");
    }

    @Test
    @DisplayName("没有 token：拒绝（URL 里有 userId 不算数）")
    void rejectsMissingToken() {
        assertFalse(handshake(req("/api/ws/1001", null, null), attrs()),
                "这是被修掉的那个洞：以前只要 URL 里有 userId 就直接注册");
    }

    @Test
    @DisplayName("token 是乱码：拒绝，但不能抛异常把连接线程带崩")
    void rejectsGarbageToken() {
        assertFalse(handshake(req("/api/ws/1001", "token", "not-a-jwt"), attrs()));
    }

    @Test
    @DisplayName("空串 token：拒绝（等价于没传）")
    void rejectsBlankToken() {
        assertFalse(handshake(req("/api/ws/1001", "token", "   "), attrs()));
    }

    @Test
    @DisplayName("token 里没有 user_id claim：拒绝")
    void rejectsTokenWithoutUserIdClaim() {
        String token = JwtUtil.createJWT(TTL, Map.of("username", "someone"));

        assertFalse(handshake(req("/api/ws/1001", "token", token), attrs()));
    }

    @Test
    @DisplayName("已过期的 token：拒绝")
    void rejectsExpiredToken() {
        String expired = JwtUtil.createJWT(-1000L, Map.of("user_id", 1001L, "username", "u"));

        assertFalse(handshake(req("/api/ws/1001", "token", expired), attrs()));
    }

    @Test
    @DisplayName("URL 里的 userId 不是数字：同样不能通过相等性比对")
    void rejectsNonNumericPathSegment() {
        // 路径形如 /api/ws/not-a-number：claimedUserId = "not-a-number"，与 token 不一致 → 拒
        assertFalse(handshake(req("/api/ws/not-a-number", "token", tokenFor(1001)), attrs()));
    }

    @Test
    @DisplayName("查询参数里的 token 也要做身份比对，不能只查头")
    void queryParamAlsoSubjectToIdentityCheck() {
        Map<String, Object> attrs = attrs();

        boolean ok = handshake(reqWithQuery("/api/ws/9999", "token", tokenFor(1001)), attrs);

        assertFalse(ok, "换了传递通道，判定标准不能变松 —— 否则就是个绕过口");
    }
}
