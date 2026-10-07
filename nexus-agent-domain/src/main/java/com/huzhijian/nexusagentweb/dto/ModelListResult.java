package com.huzhijian.nexusagentweb.dto;

import java.util.List;

/**
 * 模型列表的查询结果 + **来源元信息**（2026-10-07 新增）。
 * <p>
 * <b>为什么要把"来源"一起带出来</b>：`POST /api/chat/model` 会向厂商的
 * {@code /v1/models} 真实发一次请求，但以前失败时是**完全静默降级** ——
 * 只打一行 WARN，然后原样返回 {@code user_config} 里保存的旧模型名。
 * 前端拿到的是一份"看起来正常"的下拉框，于是就有了
 * 「这个接口是不是根本没向供应商请求？」的疑问（2026-10-07 用户原话）。
 * <p>
 * 现在把 {@link #live} 与 {@link #reason} 一并交给调用方：
 * <ul>
 *   <li>{@code live=true}：厂商真实返回的列表；</li>
 *   <li>{@code live=false}：降级结果（厂商不支持 /v1/models、401、网络不通…），
 *       {@code names} 是配置里存的旧模型名，<b>不是</b>厂商当前的真实列表。</li>
 * </ul>
 * ⚠️ 契约上 {@code Result.data} 仍然是 {@code List<String>}（模型名数组），
 * 元信息只写进 {@code Result.msg}，前端不读也不影响 —— 这是刻意的兼容设计。
 *
 * @param names  模型名列表（降级时为配置里保存的值，可能为空）
 * @param live   true = 厂商真实返回；false = 降级
 * @param reason 降级原因（成功时为 null）
 */
public record ModelListResult(List<String> names, boolean live, String reason) {
}
