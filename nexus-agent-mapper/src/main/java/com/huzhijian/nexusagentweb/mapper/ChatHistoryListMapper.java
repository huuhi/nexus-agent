package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Mapper
* @createDate 2026-04-18 11:52:04
* @Entity com.huzhijian.nexusagentweb.domain.ChatHistoryList
*/
public interface ChatHistoryListMapper extends BaseMapper<ChatHistoryList> {
    @Insert("insert into chat_history_list(session_id,user_id,title) values(#{sessionId}::uuid,#{userId},#{title})")
    void save(ChatHistoryList history);

    @Delete("delete from chat_history_list where session_id=#{sessionId}::uuid and user_id=#{userId}")
    int removeBySessionAndUserId(String sessionId, Long userId);

    /**
     * 会话重命名（P3-1 补）。
     * <p>
     * ⚠️ WHERE 里**必须带 user_id**：sessionId 是客户端可见的 UUID，
     * 只按 session_id 更新等于「改个参数就能改别人会话的标题」（越权写）。
     * 返回值用于判断会话是否存在 —— 影响 0 行就是不存在或不属于当前用户。
     * <p>
     * 顺手把 update_time 写成 now()：列表接口按 update_time 倒序，
     * 不更新的话用户改名后会话不会排到前面，看起来像"没生效"。
     */
    @Update("update chat_history_list set title=#{title}, update_time=now() "
            + "where session_id=#{sessionId}::uuid and user_id=#{userId}")
    int updateTitleBySessionAndUserId(@Param("sessionId") String sessionId,
                                      @Param("title") String title,
                                      @Param("userId") Long userId);
}




