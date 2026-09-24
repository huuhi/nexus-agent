package com.huzhijian.nexusagentweb.context;


import com.huzhijian.nexusagentweb.service.ChatAssistant;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明: 一次对话的运行期上下文（不可变产物，由 ChatContextFactory 组装）。
 */
@Getter
@Builder
public class ChatContext {
    private ChatAssistant chatAssistant;
    private String sessionId;
    private boolean isNewSession;

    /**
     * 本次请求**选择了但连不上**的 MCP 服务名（P2-9）。
     * <p>
     * 用于在系统提示词里告知模型「该能力当前不可用」—— 否则模型只会说"我没有这个能力"，
     * 用户也分不清是"没配"还是"配了但连不上"。
     */
    @Builder.Default
    private List<String> mcpUnavailable = List.of();
}
