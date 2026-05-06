package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.ChatHistoryListMapper;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;


/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Service实现
* @createDate 2026-04-18 11:52:04
*/
@Service
@Slf4j
public class ChatHistoryListServiceImpl extends ServiceImpl<ChatHistoryListMapper, ChatHistoryList>
    implements ChatHistoryListService{

    private final OpenAiChatModel model;
    private final ChatHistoryListMapper mapper;
    private final ChatMemoryService chatMemoryService;

    public ChatHistoryListServiceImpl(OpenAiChatModel model1, ChatHistoryListMapper mapper, ChatMemoryService chatMemoryService) {
        this.model = model1;
        this.mapper = mapper;
        this.chatMemoryService = chatMemoryService;
    }

    @Override
    @Async
    public void createTitle(String sessionId, String message,String answer,Long userId) {
//        异步生成标题
        SystemMessage systemMessage = SystemMessage.from("""
            根据用户的问题及AI的回答生成标题。只返回标题内容，不允许返回其他任何无关内容，不允许自言自语，不要加"标题："等前缀。
                 问题：%s
                 回答：%s
            """.formatted(message, answer));
        ChatResponse chat = model.chat(systemMessage);
        String title = chat.aiMessage().text();
        log.info("生成的标题：{}",title);
        ChatHistoryList history = ChatHistoryList.builder().sessionId(sessionId)
                .title(title)
                .userId(userId)
                .build();
        mapper.save(history);
//        TODO 发送给前端
    }

    @Override
    public void deleteSession(String sessionId) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        boolean remove = update().eq("session_id", sessionId)
                .eq("user_id", userId)
                .remove();
        if (remove) {
            chatMemoryService.delByMemoryId(sessionId);
        }
    }

    @Override
    public List<ChatHistoryList> getList() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            return List.of();
        }
        return query().eq("user_id", userId).list();

    }
}




