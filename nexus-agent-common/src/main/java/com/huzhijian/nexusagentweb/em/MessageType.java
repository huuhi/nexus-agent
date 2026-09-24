package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public enum MessageType {
    USER("USER"),
    AI("AI"),
    THINK("THINK"),
    CONTENT("CONTENT"),
    TOOL_EXECUTION("TOOL_EXECUTION"),
    TOOL_EXECUTION_RESULT("TOOL_EXECUTION_RESULT"),
    /** 运行失败：SSE 的 error 事件带 trace_id（runId），便于把前端看到的错误与服务端日志对上 */
    ERROR("ERROR"),
    CUSTOM("CUSTOM");
    @Getter
    private final String value;
    MessageType(String value){
        this.value=value;
    }
}
