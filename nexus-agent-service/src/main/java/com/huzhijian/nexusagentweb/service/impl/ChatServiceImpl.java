package com.huzhijian.nexusagentweb.service.impl;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.RunContext;
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
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {
    private final ChatContextFactory chatContextFactory;
    private final ChatHistoryListService chatHistoryListService;
    private final ChatMessageConverter converter;

    @Override
    public SseEmitter chat(ChatDTO chatDTO) {

        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录!");
        }
        SseEmitter sseEmitter = new SseEmitter(120000L);

        List<ChatUserMessage> messages = chatDTO.messages();

//        1) 先转换用户消息：附件元数据由返回值带回，不再写 ThreadLocal
        ChatMessageConverter.ConvertedMessage converted;
        try {
            converted = converter.toContents(messages);
        } catch (ClientException e) {
            throw new ValidationException("参数错误!");
        } catch (IOException e) {
            throw new ParserFileException("解析文件失败!");
        }

//        2) 组装本次运行上下文。后续流式回调运行在线程池里，
//        用户ID 与附件元数据只能通过这个对象带过去（ThreadLocal 在那里取不到值）
        String incomingSessionId = chatDTO.sessionId();
        boolean isNewSession = incomingSessionId == null || incomingSessionId.isEmpty();
        String sessionId = isNewSession ? UUID.randomUUID().toString() : incomingSessionId;
        RunContext runContext = new RunContext(userId, sessionId, isNewSession, converted.metadata());

//        3) 构建对话上下文（内部会把 runContext 绑定到记忆存储上）
        ChatContext chatContext = chatContextFactory.create(chatDTO, runContext);
        ChatAssistant chatAssistant = chatContext.getChatAssistant();

        TokenStream tokenStream =chatAssistant.chat(converted.contents(),sessionId);

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
                .onPartialToolCallWithContext(writer::writeToolRequestWithStream)
                .onToolExecuted(consumer->{
                    ToolExecutionRequest request = consumer.request();
//                    writer.writeToolRequest(request.id(),request.name(),request.arguments());
                    writer.writeToolResult(request,consumer.hasFailed(),consumer.result());
                })
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
