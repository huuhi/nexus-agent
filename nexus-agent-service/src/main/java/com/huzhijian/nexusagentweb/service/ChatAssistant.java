package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.UserLongMemory;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.*;

import java.util.List;

import static com.huzhijian.nexusagentweb.content.ModelSystemContent.CHAT_PROMPT;
import static com.huzhijian.nexusagentweb.content.ModelSystemContent.GET_MEMORY;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatAssistant {

    @SystemMessage(CHAT_PROMPT)
    TokenStream chat(@UserMessage List<Content>contents,@V("sessionId") @MemoryId Object memoryId,@V("picture") String picture );

    @SystemMessage(GET_MEMORY)
    @UserMessage({
            "之前的记忆:{{old_memory}}","最新的聊天{{chats}}"
    })
    List<UserLongMemory>  memoryList(@V("old_memory")String oldMemory,@V("chats")String chats);

}
