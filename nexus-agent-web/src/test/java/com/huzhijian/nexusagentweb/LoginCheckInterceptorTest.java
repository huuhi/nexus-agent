package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.interceptor.LoginCheckInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginCheckInterceptor} 的纯单测（用 spring-test 的 Mock 对象，不启容器）。
 * <p>
 * 覆盖两条最容易出错、也最危险的约定：
 * <ol>
 *   <li>没有 {@code token} 头 → HTTP 401 + JSON {@code {"code":1,"msg":"NOT_LOGIN"}}</li>
 *   <li>请求结束后必须清掉 {@code UserContextHolder} 的 ThreadLocal</li>
 * </ol>
 */
class LoginCheckInterceptorTest {

    private final LoginCheckInterceptor interceptor = new LoginCheckInterceptor();

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    @Test
    @DisplayName("无 token 头 → 401，且响应体是 JSON 的 Result（不是纯文本）")
    void rejectWhenNoToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertFalse(proceed, "未登录必须中断请求");
        assertEquals(401, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":1"), "应是 Result 信封，实际：" + body);
        assertTrue(body.contains("NOT_LOGIN"), "应带 NOT_LOGIN 提示，实际：" + body);
    }

    @Test
    @DisplayName("空字符串的 token 也按未登录处理（不能因为非空就放行）")
    void rejectWhenTokenBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/history");
        request.addHeader("token", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("afterCompletion 必须清掉 ThreadLocal（否则线程复用时会串到别人的 userId）")
    void clearsUserContextAfterCompletion() {
        UserContextHolder.saveId(42L);
        assertEquals(42L, UserContextHolder.getUserId());

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new Object(), null);

        assertNull(UserContextHolder.getUserId(), "请求结束后 ThreadLocal 必须是干净的");
    }
}
