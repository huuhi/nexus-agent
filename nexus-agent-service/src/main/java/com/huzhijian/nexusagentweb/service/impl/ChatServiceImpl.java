package com.huzhijian.nexusagentweb.service.impl;

import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.UserConfigContextHolder;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.service.SkillMcpInformationService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.skills.Skills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    private final StreamingChatModel model;
    private final SkillMcpInformationService skillMcpInformationService;
    private final PgChatMemoryStore chatMemoryStore;

    public ChatServiceImpl(StreamingChatModel model, SkillMcpInformationService skillMcpInformationService, PgChatMemoryStore chatMemoryStore) {
        this.skillMcpInformationService = skillMcpInformationService;
        this.chatMemoryStore = chatMemoryStore;
        Long userConfig = UserConfigContextHolder.getUserConfig();
        this.model = model;
        //TODO 使用用户的配置！
//        this.model =OpenAiStreamingChatModel.builder().build();
    }


    @Override
    public SseEmitter chat(ChatDTO chatDTO) {
        SseEmitter sseEmitter = new SseEmitter(120000L);
        AtomicBoolean isFinished = new AtomicBoolean();
        sseEmitter.onCompletion(()->isFinished.set(true));
        sseEmitter.onTimeout(()->isFinished.set(true));
        sseEmitter.onError((ex)->isFinished.set(true));
        String temp=chatDTO.getSessionId();
        String sessionId =(temp==null||temp.isEmpty())? UUID.randomUUID().toString():temp;


        Skills skills=skillMcpInformationService.getSkills(chatDTO.getSkills());
        McpToolProvider mcpToolProvider=skillMcpInformationService.getMcp(chatDTO.getMCPs());

        AiServices<ChatAssistant> chatAssistantAiServices = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> TokenWindowChatMemory.builder()
                        .chatMemoryStore(chatMemoryStore)
                        .maxTokens(8000, new OpenAiTokenCountEstimator("gpt-4o"))
                        .id(sessionId)
                        .build());
        if (skills!=null){
            chatAssistantAiServices.toolProvider(skills.toolProvider());
        }
        if (mcpToolProvider!=null){
            chatAssistantAiServices.toolProvider(mcpToolProvider);
        }
        ChatAssistant chatAssistant = chatAssistantAiServices.build();
        TokenStream tokenStream = chatAssistant.chat(chatDTO.getMessage(),sessionId);
        tokenStream.onPartialThinking(thinking -> {
//            思考
            if (isFinished.get()) return;
            try {
                MessageVO chunk = MessageVO.builder()
                        .type(MessageType.THINK)
                        .thinking(thinking.text()).build();
                sseEmitter.send(
                        SseEmitter.event().name("message")
                        .data(chunk));
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        }).onPartialResponse(token -> {
//            正文
            if (isFinished.get()) return;
            try {
                MessageVO chunk = MessageVO.builder()
                        .type(MessageType.CONTENT)
                        .content(token).build();
                sseEmitter.send(SseEmitter.event().name("message")
                        .data(chunk));
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        }).onCompleteResponse(response -> {
//            完毕
            if (isFinished.get()) return;
            try {
                sseEmitter.send(SseEmitter.event().name("finish").data("DONE"));
                sseEmitter.complete();
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
            log.info("思考内容:{},完整回复:{},Token消耗:{}",response.aiMessage().thinking(), response.aiMessage().text(), response.tokenUsage());
        }).onToolExecuted(consumer->{

        }).onPartialToolCallWithContext((toolCall,toolCallContext)->{

        }).onError(error -> {

        }).start();
        return sseEmitter;
    }
}
