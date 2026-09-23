package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 查询模型列表的请求体。
 * <p>
 * 历史实现用 {@code GET /api/chat/model?baseUrl=&token=}，把用户 API Key 放在 query string 里，
 * 会被网关、Nginx access log、浏览器历史等各处记录下来。改为 POST + 请求体传递。
 */
public record ModelListDTO(
        @NotBlank(message = "baseUrl 不能为空！") String baseUrl,
        @NotBlank(message = "token 不能为空！") String token) {
}
