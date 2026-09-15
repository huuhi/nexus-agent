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
    CUSTOM("CUSTOM");
    @Getter
    private final String value;
    MessageType(String value){
        this.value=value;
    }
}
