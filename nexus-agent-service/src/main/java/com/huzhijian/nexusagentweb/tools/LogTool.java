package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.domain.SystemLog;
import com.huzhijian.nexusagentweb.service.SystemLogService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/6
 * 说明:
 */
@Component
public class LogTool {
    private final SystemLogService systemLogService;

    public LogTool(SystemLogService systemLogService) {
        this.systemLogService = systemLogService;
    }

    @Tool(name = "record_log",value = "将具体反馈信息写清楚，内容不超过1000个字")
    public String recordLog(String message) {
        SystemLog log = SystemLog.builder()
                .aiMessage(message)
                .type("AI")
                .build();
        systemLogService.save(log);
        return "success";
    }

}
