package com.huzhijian.nexusagentweb.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatAssistant {
    TokenStream chat(@UserMessage String msg, @V("sessionId") @MemoryId Object memoryId);
}
