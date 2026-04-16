package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Service
* @createDate 2026-04-16 20:02:49
*/
public interface ChatMemoryService extends IService<ChatHistory> {
    List<ChatHistory> getByMemoryId(Object memory);
    void delByMemoryId(Object memoryId);
    void insertBatch(List<ChatHistory> list,Long userId);


//    List<MessageVO> getHistory(String sessionId);

    int getCountBySessionID(String sessionId);
}
