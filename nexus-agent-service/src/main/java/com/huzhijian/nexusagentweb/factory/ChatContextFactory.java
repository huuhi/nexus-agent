package com.huzhijian.nexusagentweb.factory;

import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.UserConfigContextHolder;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.tools.RagTool;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明:
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatContextFactory {
    private final StreamingChatModel defaultModel;
    private final PgChatMemoryStore chatMemoryStore;
    private final RagTool ragTool;
    private final McpInformationService mcpInformationService;


    public ChatContext create(ChatDTO chatDTO,Long userId){
        StreamingChatModel  model=createModel(userId);
        String temp=chatDTO.sessionId();
//        是否为新的对话，如果是，创建新的会话ID，并且
        boolean isNewSession=temp==null||temp.isEmpty();
        String sessionId =isNewSession? UUID.randomUUID().toString():temp;
        McpToolProvider mcp = mcpInformationService.getMcp(chatDTO.MCPs());

        AiServices<ChatAssistant> builder = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory
                        .builder().maxMessages(20)
                        .chatMemoryStore(chatMemoryStore)
                        .id(sessionId)
                        .build());

        if (chatDTO.enableRag()){
            builder.tools(ragTool);
        }
        if (mcp!=null){
            builder.toolProvider(mcp);
        }
        ChatAssistant chatAssistant = builder.build();
        return ChatContext.builder().chatAssistant(chatAssistant)
                .sessionId(sessionId)
                .isNewSession(isNewSession)
                .build();
    }

    private StreamingChatModel createModel(Long userId) {
        Long userConfig = UserConfigContextHolder.getUserConfig(userId);
        if (userConfig!=null){
//            构造模型
            return null;
        }
        return defaultModel;
    }
}
