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

import java.io.IOException;

@Slf4j
@Component
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

    private boolean reject(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.error(msg)));
        return false;
    }
}
