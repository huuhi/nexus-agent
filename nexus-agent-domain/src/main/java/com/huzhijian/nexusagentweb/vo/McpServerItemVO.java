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

    /**
     * 🔴 <b>2026-10-05 新增</b>：这一项是否已经在<b>当前用户</b>的已配置列表里。
     * <p>
     * 只由 {@code GET /api/mcp/service}（服务商预置列表）填充 —— 它是「能不能点添加」的唯一依据。
     * 没有它的时候，前端只能拿 {@code strId} 自己去比对 {@code GET /api/mcp} 的结果，
     * 而 strId 可能是 <b>null / 空串 / 带前后空格</b>（历史脏数据），比对必然有漏 ——
     * 表现就是「明明已经添加过了，预置列表里还挂着『添加』按钮，点一下又插一条」。
     * 现在由后端直接给答案，前端只看这一个布尔值即可。
     * <p>
     * {@code GET /api/mcp}（已配置列表）里恒为 {@code true}（它们本来就是已添加的）。
     */
    private Boolean added;

    /**
     * 已添加时，本地 {@code mcp_information} 记录的主键（字符串形式，避免前端 JS 精度问题）。
     * <p>
     * 用途：预置列表里「已添加」的卡片要能直接跳到编辑 / 删除，
     * 而不用前端先去 {@code GET /api/mcp} 里按 strId 反查一遍。
     * 未添加时为 {@code null}。
     */
    private String localId;
}