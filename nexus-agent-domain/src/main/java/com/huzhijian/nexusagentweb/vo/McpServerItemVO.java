package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * MCP服务器信息视图对象
 * <p>
 * ⚠️ 2026-10-04 补注释：这个 VO **刻意没有 header 字段**。
 * header 里装的是用户自己的鉴权凭据（Authorization / X-Api-Key），
 * 列表接口一并返回就等于把凭据摊给任何能调这个接口的地方。
 * 需要查看/编辑凭据请走 {@code GET /api/mcp/{id}}（{@link McpDetailVO}）。
 * <p>
 * 也正因为这里没有 header，<b>从服务商预置列表「一键添加」的请求必然不带 header</b> ——
 * 后端必须能处理 header 缺省（见 {@code McpInformationServiceImpl#toHeaderJson}）。
 */
@Data
@Builder
@AllArgsConstructor
public class McpServerItemVO {
    private String strId;
    private String id;
    private String url;
    private String description;
    private String name;
    private String logoUrl;
    private String type;
    private Boolean available;
}