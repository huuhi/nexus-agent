package com.huzhijian.nexusagentweb.context;

import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import lombok.Builder;
import lombok.Getter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明:
 */
@Getter
@Builder
public class ChatContext {
    private ChatAssistant chatAssistant;
    private String sessionId;
    private boolean isNewSession;
}
