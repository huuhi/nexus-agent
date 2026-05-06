package com.huzhijian.nexusagentweb.service.impl;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.MessageMetadataContext;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.ChatContextFactory;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    private final ChatContextFactory chatContextFactory;
    private final ChatHistoryListService chatHistoryListService;
    private final ChatMessageConverter converter;
    public ChatServiceImpl(ChatContextFactory chatContextFactory, ChatHistoryListService chatHistoryListService, ChatMessageConverter converter) {
        this.chatContextFactory = chatContextFactory;
        this.chatHistoryListService = chatHistoryListService;
        this.converter = converter;
    }
    @Override
    public SseEmitter chat(ChatDTO chatDTO) {

        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录!");
        }
        SseEmitter sseEmitter = new SseEmitter(120000L);

        ChatContext chatContext = chatContextFactory.create(chatDTO, userId);
        ChatAssistant chatAssistant = chatContext.getChatAssistant();
        String sessionId = chatContext.getSessionId();
        boolean isNewSession = chatContext.isNewSession();
        List<ChatUserMessage> messages = chatDTO.messages();

        List<Content> contents;
        try {
            contents = converter.toContents(messages);
        } catch (ClientException e) {
            throw new ValidationException("参数错误!");
        } catch (IOException e) {
            throw new ParserFileException("解析文件失败!");
        }
        TokenStream tokenStream;
        try {
            tokenStream = chatAssistant.chat(contents,sessionId);
        } finally {
            MessageMetadataContext.clear();
        }

        SseResponseConverter writer = SseResponseConverter.builder().chatHistoryListService(chatHistoryListService)
                .sessionId(sessionId)
                .isNewSession(isNewSession)
                .message(converter.extractFirstText(messages)).userId(userId)
                .sseEmitter(sseEmitter)
                .build();

        sseEmitter.onCompletion(writer::finish);
        sseEmitter.onTimeout(()->writer.onError(new Throwable("超时！")));
        sseEmitter.onError(writer::onError);

        tokenStream.onPartialThinking(writer::writeThinking)
                .onPartialResponse(writer::writeContent)
                .onToolExecuted(consumer->{
                    ToolExecutionRequest request = consumer.request();
                    writer.writeToolResult(request,consumer.hasFailed(),consumer.result());
                })
                .onPartialToolCall(writer::writeToolRequest)
                .onCompleteResponse(response -> writer.finish())
                .onError(writer::onError)
                .start();
        return sseEmitter;
    }

    @Override
    public List<String> getModelList(String baseUrl, String token) {
        List<ModelDescription> listModels = OpenAiModelCatalog
                .builder()
                .apiKey(token)
                .baseUrl(baseUrl)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build().listModels();
        return listModels.stream().map(ModelDescription::name).toList();
    }

}
