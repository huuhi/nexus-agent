package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

/**
 * SSE 事件名（**契约 v2**，P2-5）。
 * <p>
 * 存在的理由：v1 里事件名一半是代码里写死的小写字面量（`message`/`session_id`/`finish`），
 * 另一半是 {@link MessageType} 的枚举值（**全大写**的 `TOOL_EXECUTION`），
 * 前端按 `event: tool_execution` 监听会永远收不到。现在统一收敛到**这一个枚举**：
 * <ul>
 *   <li>全部**小写**；</li>
 *   <li>SSE 的 `event:` 与信封里的 `event` 字段**取同一个值**，不会漂移；</li>
 *   <li>新增事件必须先在这里加，等于强制走一遍契约评审。</li>
 * </ul>
 *
 * @see com.huzhijian.nexusagentweb.vo.SseEvent
 */
@Getter
public enum SseEventType {

    /**
     * 首帧：告诉前端本次运行的 `runId`（= trace_id）与 `sessionId`。
     * <p>
     * v1 里 `sessionId` 只在**新会话**、且**流结束时**才给 —— 前端在整段回答结束前
     * 不知道该往哪个会话发下一条消息。`run` 放在第一帧解决这个时序问题。
     */
    RUN("run"),

    /** 思考 / 正文增量（P2-12 起是"批量增量"，一帧可能包含多个 token） */
    MESSAGE("message"),

    /** 工具调用请求（`arguments` 是流式片段，同一个调用会被拆成多帧） */
    TOOL_EXECUTION("tool_execution"),

    /** 工具执行结果 */
    TOOL_EXECUTION_RESULT("tool_execution_result"),

    /** AI 产出的交付物（P2-10），前端渲染成下载卡片 */
    ARTIFACT("artifact"),

    /** 正常结束 */
    FINISH("finish"),

    /** 运行失败。带 `runId`，可直接在服务端日志里 grep `RUN runId=<值>` */
    ERROR("error"),

    /**
     * @deprecated v1 的「新会话 ID」事件，v2 已并入 {@link #RUN}（且提前到首帧）。
     *     保留枚举值只为文档追溯，**不再下发**。
     */
    @Deprecated
    SESSION_ID("session_id"),
    ;

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }
}
