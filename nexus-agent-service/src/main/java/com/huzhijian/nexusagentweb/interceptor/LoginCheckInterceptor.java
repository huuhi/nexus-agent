package com.huzhijian.nexusagentweb.interceptor;


import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import com.huzhijian.nexusagentweb.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Slf4j
@Component
// 与 WebInterceptorConfig 用同一个开关：关闭鉴权时两者一起不生效，避免只关一半
@ConditionalOnProperty(
        name = "nexus.agent.security.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class LoginCheckInterceptor implements HandlerInterceptor {

    //在请求处理之前调用，返回 true 表示继续处理，返回 false 表示中断处理。
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String jwt = request.getHeader("token");
        if (jwt == null || jwt.isEmpty()) {
            log.debug("未登录请求 - Method: {}, URI: {}", request.getMethod(), request.getRequestURI());
            return reject(response, "NOT_LOGIN");
        }

        try {
            Long id = JwtUtil.getIdFromToken(jwt, "user_id");
            // ⚠️ 必须判断 null：getIdFromToken 在 token 过期/签名不符/伪造时返回 null 而**不抛异常**。
            // 历史实现只 catch 异常，于是无效 token 会「通过」拦截器并写入 null 用户ID，
            // 结果是要么在业务层才报「用户未登录!」（难以定位），
            // 要么被不校验 userId 的接口当成已登录处理（越权风险）。
            if (id == null) {
                log.warn("token 无效（解析不出 user_id，可能已过期或签名不符）");
                return reject(response, "NOT_LOGIN");
            }
            UserContextHolder.saveId(id);
        } catch (Exception e) {
            log.warn("JWT解析失败: {}", e.getMessage());
            return reject(response, "NOT_LOGIN");
        }

        return true;
    }

    /**
     * 请求结束后**必须**清掉 ThreadLocal。
     * <p>
     * 原来只在 {@code preHandle} 里 {@code saveId}，从不清空：
     * 一旦处理请求的线程被复用（关掉虚拟线程、换成线程池、或将来接入异步 Servlet），
     * 下一个请求会读到**上一个用户**的 userId —— 那是实打实的越权，
     * 而且现象是"偶尔看到别人的数据"，极难复现和定位。
     * <p>
     * 现在开着虚拟线程（{@code spring.threads.virtual.enabled=true}，一请求一线程）
     * 时不会立刻炸，所以这个修法是**把隐患消掉**，不是修一个正在发生的故障。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContextHolder.removeUserId();
    }

    private boolean reject(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.error(msg)));
        return false;
    }
}
