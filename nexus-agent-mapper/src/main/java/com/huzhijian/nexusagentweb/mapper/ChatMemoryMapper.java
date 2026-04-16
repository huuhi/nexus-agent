package com.huzhijian.nexusagentweb.mapper;

import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Mapper
* @createDate 2026-04-16 20:02:49
* @Entity com.huzhijian.nexusagentweb.domain.ChatMemory
*/
public interface ChatMemoryMapper extends BaseMapper<ChatHistory> {
    List<ChatHistory> getAllByMemoryId(Object sessionId);

    void delAllByMemoryId(Object sessionId);

    boolean insertBatch(List<ChatHistory> list,Long userId);

    @Select("select count(id) from chat_memory where session_id=#{sessionId}::uuid")
    int getCountByMemoryId(String sessionId);
}




