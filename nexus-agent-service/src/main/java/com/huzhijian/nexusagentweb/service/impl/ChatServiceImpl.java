package com.huzhijian.nexusagentweb.service.impl;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.MessageMetadataContext;
import com.huzhijian.nexusagentweb.context.UserConfigContextHolder;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.em.UserMessageType;
import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.service.SkillMcpInformationService;
import com.huzhijian.nexusagentweb.tools.RagTool;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.*;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.skills.Skills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.huzhijian.nexusagentweb.content.MetadataKeyContent.*;

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
    private final RagTool ragTool;
    private final FileUtils fileUtils;
    private final ChatHistoryListService chatHistoryListService;

    public ChatServiceImpl(StreamingChatModel model, SkillMcpInformationService skillMcpInformationService, PgChatMemoryStore chatMemoryStore, RagTool ragTool, FileUtils fileUtils, ChatHistoryListService chatHistoryListService) {
        this.skillMcpInformationService = skillMcpInformationService;
        this.chatMemoryStore = chatMemoryStore;
        this.ragTool = ragTool;
        this.fileUtils = fileUtils;
        this.chatHistoryListService = chatHistoryListService;
        Long userConfig = UserConfigContextHolder.getUserConfig();
        this.model = model;
        //TODO 使用用户的配置！
//        this.model =OpenAiStreamingChatModel.builder().build();
    }


    @Override
    public SseEmitter chat(ChatDTO chatDTO) {

        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录!");
        }
        SseEmitter sseEmitter = new SseEmitter(120000L);
        AtomicBoolean isFinished = new AtomicBoolean();
        sseEmitter.onCompletion(()->isFinished.set(true));
        sseEmitter.onTimeout(()->isFinished.set(true));
        sseEmitter.onError((ex)->isFinished.set(true));
        StringBuilder answer=new StringBuilder();
        String temp=chatDTO.getSessionId();
//        是否为新的对话，如果是，创建新的会话ID，并且
        boolean isNewSession=temp==null||temp.isEmpty();
        String sessionId =isNewSession? UUID.randomUUID().toString():temp;


        Skills skills=skillMcpInformationService.getSkills(chatDTO.getSkills());
        McpToolProvider mcpToolProvider=skillMcpInformationService.getMcp(chatDTO.getMCPs());

        AiServices<ChatAssistant> chatAssistantAiServices = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .tools(ragTool)
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
        List<ChatUserMessage> messages = chatDTO.getMessages();

        List<Content> contents;
        try {
            contents = getContents(messages);
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
        tokenStream.onPartialThinking(thinking -> {
//            思考
            if (isFinished.get()) return;
            try {
                MessageVO chunk = MessageVO.builder()
                        .type(MessageType.THINK.getValue())
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
            answer.append(token);
            try {
                MessageVO chunk = MessageVO.builder()
                        .type(MessageType.CONTENT.getValue())
                        .content(token).build();
                sseEmitter.send(SseEmitter.event().name("message")
                        .data(chunk));
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        }).onToolExecuted(consumer->{
//            consumer 调用完成
            ToolExecutionRequest request = consumer.request();
            MessageVO.ToolResultVO resultVO = MessageVO.ToolResultVO.builder()
                    .id(request.id()).isError(consumer.hasFailed())
                    .toolName(request.name())
                    .result(consumer.result()).build();
            MessageVO messageVO = MessageVO.builder()
                    .type(MessageType.TOOL_EXECUTION_RESULT.getValue())
                    .toolResultVO(resultVO)
                    .build();
            try {
                sseEmitter.send(SseEmitter.event().name(
                        MessageType.TOOL_EXECUTION_RESULT.getValue())
                        .data(messageVO).build());
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }

        }).onPartialToolCall(toolCall -> {
//            没有上下文，调用过程中,返回AI目前正在调用工具
            MessageVO.ToolRequestVO toolRequestVO = MessageVO.ToolRequestVO.builder()
                    .toolName(toolCall.name())
                    .arguments(toolCall.partialArguments())
                    .id(toolCall.id())
                    .build();
            MessageVO msg = MessageVO.builder()
                    .type(MessageType.TOOL_EXECUTION.getValue())
                    .toolRequestList(List.of(toolRequestVO))
                    .build();
            try {
                sseEmitter.send(SseEmitter.event()
                        .data(msg)
                        .name(MessageType.TOOL_EXECUTION.getValue()).build());
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        }).onCompleteResponse(response -> {
//            完毕
            if (isFinished.get()) return;
            try {
//                判断是否为新对话，如果是添加标题，插入会话列表
                if (isNewSession){
                    // 根据用户的问题和回答，异步生成标题
                    chatHistoryListService.createTitle(sessionId,getUserMessage(messages),answer.toString(),userId);
                }
                sseEmitter.send(SseEmitter.event().name("finish").data("DONE"));
                sseEmitter.complete();
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
            log.info("思考内容:{},完整回复:{},Token消耗:{}",response.aiMessage().thinking(), response.aiMessage().text(), response.tokenUsage());
        }).onError(error -> {

        }).start();
        return sseEmitter;
    }
    private String getUserMessage(List<ChatUserMessage> messages){
        // TODO 理论上，用户消息都没有的话，应该在前面就处理了（抛出错误）
        return messages.stream()
                .filter(message -> message
                        .getType().equals(UserMessageType.TEXT))
                .findFirst().orElse(ChatUserMessage.builder().content("").build()).getContent();
    }

    private List<Content> getContents(List<ChatUserMessage> messages) throws ClientException, IOException {
        List<Content> contents=new ArrayList<>();
        Map<String, Object> allMetadata=new HashMap<>();
        for (ChatUserMessage message : messages) {
            Map<String, Object> metadata = message.getMetadata();
            switch (message.getType()){
                case UserMessageType.TEXT -> contents.add(TextContent.from(message.getContent()));
                case UserMessageType.FILE -> {
                    allMetadata.putAll(metadata);
                    //解析
                    String url = metadata.get(FILE_URL).toString();
                    SysFile knowledgeFile = SysFile.builder().fileUrl(url).extension(metadata.get(FILE_TYPE).toString()).build();
                    Document document = fileUtils.getDocument(knowledgeFile);
                    String fileText = """
                    文件%s,的内容：%s
                    """.formatted(metadata.get(FILE_NAME),document.toTextSegment().text());
                    contents.add(TextContent.from(fileText));
                }
                case UserMessageType.IMAGE -> {
                    String url = metadata.get(FILE_URL).toString();
                    allMetadata.putAll(metadata);
                    contents.add(ImageContent.from(url));
                }
            }
        }
        MessageMetadataContext.set(allMetadata);
        return contents;
    }

}
