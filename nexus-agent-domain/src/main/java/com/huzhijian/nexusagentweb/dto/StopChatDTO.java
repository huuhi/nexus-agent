package com.huzhijian.nexusagentweb.dto;

/**
 * 「停止生成」的请求体（2026-10-06 新增）。
 *
 * <p><b>二选一即可</b>，优先用 {@code runId}：
 * <ul>
 *   <li>{@code runId} —— SSE 首帧 {@code run} 事件里就有，最精确
 *       （万一同一会话未来支持并发运行，只有它能停对那一次）；</li>
 *   <li>{@code sessionId} —— 前端手上只有会话时的兜底，
 *       停的是该会话<b>当前正在跑</b>的那一次。</li>
 * </ul>
 *
 * <p>⚠️ 本类在 domain 模块，<b>不能写 {@code @Schema}</b> —— 该模块没有 swagger 依赖，
 * 加了会直接编译失败。DTO 一律用普通 javadoc 描述字段。
 *
 * @param runId     本次运行 id（首帧 {@code run} 事件的 {@code data.runId}）
 * @param sessionId 会话 id（拿不到 runId 时用它反查）
 * @author 胡志坚
 */
public record StopChatDTO(String runId, String sessionId) {

    /**
     * 两个都没传时返回一句明确的提示，否则返回 {@code null}。
     * <p>
     * 单独抽出来是为了让「参数缺失」也能给出可读原因 ——
     * 前端拿到这句话就知道是漏传 runId，而不是后端 500。
     */
    public String describeMissing() {
        boolean noRunId = runId == null || runId.isBlank();
        boolean noSessionId = sessionId == null || sessionId.isBlank();
        if (noRunId && noSessionId) {
            return "runId 与 sessionId 至少要传一个（runId 取自 SSE 首帧 run 事件的 data.runId）";
        }
        return null;
    }
}
