package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.domain.SystemLog;
import com.huzhijian.nexusagentweb.service.SystemLogService;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/6
 * 说明:
 */
@Component
public class LogTool implements AgentToolSet {

    @Override
    public String key() {
        return "log";
    }

    @Override
    public String description() {
        return "系统日志：让 AI 记录缺失的工具或系统不足";
    }

    private final SystemLogService systemLogService;
    private final ToolCallGuard toolCallGuard;

    public LogTool(SystemLogService systemLogService, ToolCallGuard toolCallGuard) {
        this.systemLogService = systemLogService;
        this.toolCallGuard = toolCallGuard;
    }

    @Tool(name = "record_log",value = "将具体反馈信息写清楚，内容不超过1000个字")
    public String recordLog(@ToolMemoryId Object memoryId,
                            @P("反馈信息，不超过 1000 字") String message) {
        String blocked = toolCallGuard.interceptText(memoryId, "record_log",
                ToolCallGuard.fingerprint(message));
        if (blocked != null) {
            return blocked;
        }
        SystemLog log = SystemLog.builder()
                .aiMessage(message)
                .type("AI")
                .build();
        try {
            systemLogService.save(log);
        } catch (Exception e) {
            return e.getMessage();
        }
        return "success";
    }

}
