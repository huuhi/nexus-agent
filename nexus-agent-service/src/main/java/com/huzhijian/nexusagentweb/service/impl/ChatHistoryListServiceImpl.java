package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.mapper.ChatHistoryListMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;



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

    public ChatHistoryListServiceImpl(OpenAiChatModel model1, ChatHistoryListMapper mapper) {
        this.model = model1;
        this.mapper = mapper;
    }

    @Override
    @Async
    public void createTitle(String sessionId, String message,String answer,Long userId) {
//        TODO 这个方法之后可以考虑写成异步
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
    }
}




