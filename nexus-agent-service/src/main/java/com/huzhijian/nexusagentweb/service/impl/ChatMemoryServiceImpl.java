package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Service实现
* @createDate 2026-04-16 20:02:49
*/
@Service
public class ChatMemoryServiceImpl extends ServiceImpl<ChatMemoryMapper, ChatHistory>
    implements ChatMemoryService{

    @Resource
    private ChatMemoryMapper mapper;

    @Override
    public List<ChatHistory> getByMemoryId(Object memory) {
//        输入任意字符，则过滤工具消息
        return mapper.getAllByMemoryId(memory);
    }

    @Override
    public void delByMemoryId(Object memoryId) {
        mapper.delAllByMemoryId(memoryId);
    }

    @Override
    public void insertBatch(List<ChatHistory> list,Long userId) {
        if (list.isEmpty()){
            log.debug("插入失败,消息为空");
            return;
        }
        mapper.insertBatch(list, userId);
    }
    @Override
    public int getCountBySessionID(String sessionId) {
        return mapper.getCountByMemoryId(sessionId);
    }
}




