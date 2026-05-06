package com.huzhijian.nexusagentweb.service;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.*;

import java.util.List;

import static com.huzhijian.nexusagentweb.content.ModelSystemContent.CHAT_PROMPT;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatAssistant {

    @SystemMessage(CHAT_PROMPT)
    TokenStream chat(@UserMessage List<Content>contents, @V("sessionId") @MemoryId Object memoryId);
}
