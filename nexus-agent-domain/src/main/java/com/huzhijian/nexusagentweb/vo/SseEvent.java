package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

/**
 * SSE 事件的**统一信封**（**契约 v2**，P2-5）。
 * <p>
 * v1 里每个事件的 data 形状都不一样（`message` 是 MessageVO、`session_id` 是裸字符串、
 * `finish` 是 `"DONE"`），前端要为每种事件写一套解析。v2 所有事件都套这个信封：
 *
 * <pre>{@code
 * event: tool_execution
 * id: 7
 * data: {"seq":7,"runId":"a1b2c3","event":"tool_execution","data":{...}}
 * }</pre>
 *
 * <ul>
 *   <li>{@code seq}：本次 Run 内**从 1 开始单调递增**。前端可用它判断"有没有丢帧"
 *       （发现跳号说明中间断了）。同时写进 SSE 原生 `id:` 字段 ——
 *       浏览器 `EventSource` 会自动记住它，断线重连时带上 `Last-Event-ID`。</li>
 *   <li>{@code runId}：本次 Run 的 trace_id，与服务端 `RUN runId=...` 日志一一对应。</li>
 *   <li>{@code event}：与 SSE `event:` 同名，冗余下发，方便从裸流里解析与打日志。</li>
 *   <li>{@code data}：事件载荷，形状按事件而定（见 `docs/sse-contract.md`）。</li>
 * </ul>
 *
 * @see com.huzhijian.nexusagentweb.em.SseEventType
 */
@Data
@Builder
public class SseEvent {
    /**
     * 帧序号，同一次 Run 内从 1 开始单调递增。
     * <p>
     * 🔴 <b>2026-10-08：类型是 {@code int} 而不是 {@code long}，这不是随手选的。</b>
     * 全局的 {@code JacksonConfig} 把所有 {@code Long}/{@code long} 序列化成<b>字符串</b>
     * （为雪花 ID 精度，见该类注释）。用一个 {@code long} 装序号，就会被那条规则误伤成
     * {@code "seq":"7"} —— 而本字段在契约里承诺是 number，且前端拿它做<b>跳号检测</b>
     * （{@code seq - prev !== 1} 判丢帧）。字符串参与算术会静默得出 {@code NaN}，
     * 检测永远不报警，正好是最坏的失效方式。
     * <p>
     * 序号是「Run 内的自增计数」，不是「跨表主键」，没有 2^53 精度问题，
     * 用 {@code int} 即可（单次 Run 的帧数远不可能触及 21 亿）。
     */
    private int seq;
    /** 本次运行的 trace_id */
    private String runId;
    /** 事件名，取自 {@code SseEventType.value} */
    private String event;
    /** 事件载荷 */
    private Object data;
}
