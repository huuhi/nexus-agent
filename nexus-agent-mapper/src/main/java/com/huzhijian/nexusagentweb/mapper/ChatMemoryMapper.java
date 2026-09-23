package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Mapper
* @createDate 2026-04-16 20:02:49
* @Entity com.huzhijian.nexusagentweb.domain.ChatMemory
*/
public interface ChatMemoryMapper extends BaseMapper<ChatHistory> {
    /**
     * 供流式对话的记忆读写使用（内存 ID 即 sessionId，此时还没有用户上下文）。
     * ⚠️ 对外接口一律不要用这个方法，改用 {@link #getAllByMemoryIdAndUserId}。
     */
    List<ChatHistory> getAllByMemoryId(Object sessionId);

    /**
     * 对外读取历史专用：同时限定 session_id 与 user_id，防止越权读取他人会话。
     */
    List<ChatHistory> getAllByMemoryIdAndUserId(@Param("sessionId") Object sessionId,
                                                @Param("userId") Long userId);

    void delAllByMemoryId(Object sessionId);

    boolean insertBatch(List<ChatHistory> list,Long userId);

    @Select("select count(session_id) from chat_memory where session_id=#{sessionId}::uuid")
    int getCountByMemoryId(String sessionId);
}




