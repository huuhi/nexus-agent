package com.huzhijian.nexusagentweb.config;


import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/3/25
 * 说明:
 */

@Slf4j
@Service
public class PgChatMemoryStore implements ChatMemoryStore {
    @Resource
    private ChatMemoryService chatMemoryService;

    @Override
    public List<ChatMessage> getMessages(Object o) {
        if (o==null||o=="") return List.of();
//        一般情况下memoryId都是字符串
        List<ChatHistory> chatMemories = chatMemoryService.getByMemoryId(o);
        if (chatMemories==null||chatMemories.isEmpty()) return List.of();
        return chatMemories.stream().map(entity->ChatMessageDeserializer
                .messageFromJson(entity.getContent().toString())).toList();
    }

    @Override
    @Transactional
    public void updateMessages(Object sessionId, List<ChatMessage> list) {
        if (sessionId==null||sessionId=="") throw new RuntimeException("会话ID不能为NULL/空");
//        chatMemoryService.delByMemoryId(sessionId);
        ArrayList<ChatHistory> insertList = new ArrayList<>();
        Long userId = UserContextHolder.getUserId();
//        只添加增量数据
        int count = chatMemoryService.getCountBySessionID(sessionId.toString());
        if (list.size()>count){
            List<ChatMessage> needAdd = list.subList(count, list.size());
            for (ChatMessage chatMessage : needAdd) {
                log.info("类型：{},chatMessage:{}",chatMessage.type(),chatMessage);
                String jsonString = ChatMessageSerializer.messageToJson(chatMessage);
                log.info("JSON:{}",jsonString);
                ChatHistory chatHistory = ChatHistory.builder()
                        .sessionId(sessionId)
                        .type(chatMessage.type().name())
                        .content(jsonString)
                        .build();
                insertList.add(chatHistory);
            }
        }
        chatMemoryService.insertBatch(insertList,userId);
    }

    @Override
    public void deleteMessages(Object o) {
        if (o==null||o=="") throw new RuntimeException("会话ID不能为NULL/空");
        chatMemoryService.delByMemoryId(o);
    }
}
