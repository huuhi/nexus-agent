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

    /**
     * 取会话最后一条消息的原始 JSON，供「锚点式增量写入」定位新增部分。
     * <p>
     * 之所以需要锚点：记忆窗口（TokenWindowChatMemory）会在超限时**淘汰旧消息**，
     * 此时「传入条数」不再单调增长，靠条数比较会永远判定为「没有新增」，
     * 导致长会话的新消息永远写不进库。
     *
     * @return 最后一条消息的 JSON 文本；会话不存在时返回 null
     */
    String getLastContentByMemoryId(@Param("sessionId") Object sessionId,
                                    @Param("userId") Long userId);

    /**
     * 取会话最近 {@code limit} 条消息的原始 JSON（时间正序）。
     * <p>
     * 用于锚点失配时的兜底：按内容去重，只插入库里还没有的消息。
     *
     * @see #getLastContentByMemoryId
     */
    List<String> getRecentContents(@Param("sessionId") Object sessionId,
                                   @Param("userId") Long userId,
                                   @Param("limit") int limit);

    void delAllByMemoryId(Object sessionId);

    boolean insertBatch(List<ChatHistory> list,Long userId);

    @Select("select count(session_id) from chat_memory where session_id=#{sessionId}::uuid")
    int getCountByMemoryId(String sessionId);
}




