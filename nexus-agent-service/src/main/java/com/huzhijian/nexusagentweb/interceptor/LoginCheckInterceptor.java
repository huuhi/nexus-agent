package com.huzhijian.nexusagentweb.interceptor;


import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import com.huzhijian.nexusagentweb.vo.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class LoginCheckInterceptor implements HandlerInterceptor {

    //在请求处理之前调用，返回 true 表示继续处理，返回 false 表示中断处理。
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String jwt = request.getHeader("token");
        if (jwt == null || jwt.isEmpty()) {
            log.debug("未登录请求 - Method: {}, URI: {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            Result res = Result.error("NOT_LOGIN");
            String jsonStr = JSONUtil.toJsonStr(res);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(jsonStr);
            return false;
        }

        try {
            Long id = JwtUtil.getIdFromToken(jwt, "user_id");
            UserContextHolder.saveId(id);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            Result res = Result.error("NOT_LOGIN");
            String json = JSONUtil.toJsonStr(res);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(json);
            log.warn("JWT解析失败: {}", e.getMessage());
            return false;
        }

        return true;
    }
}
