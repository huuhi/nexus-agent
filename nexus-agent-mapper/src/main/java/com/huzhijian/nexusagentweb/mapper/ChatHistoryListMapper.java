package com.huzhijian.nexusagentweb.mapper;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Mapper
* @createDate 2026-04-18 11:52:04
* @Entity com.huzhijian.nexusagentweb.domain.ChatHistoryList
*/
public interface ChatHistoryListMapper extends BaseMapper<ChatHistoryList> {
    @Insert("insert into chat_history_list(session_id,user_id,title) values(#{sessionId}::uuid,#{userId},#{title})")
    void save(ChatHistoryList history);
}




