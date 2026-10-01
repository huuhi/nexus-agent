package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.vo.ChatSessionSearchVO;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Service
* @createDate 2026-04-18 11:52:04
*/
public interface ChatHistoryListService extends IService<ChatHistoryList> {

    void createTitle(String sessionId, String message,String answer,Long userId);

    void deleteSession(String sessionId);

    List<ChatHistoryList> getList();

    /**
     * 重命名会话（P3-1 补）。
     *
     * @throws com.huzhijian.nexusagentweb.exception.UnauthorizedException 未登录
     * @throws com.huzhijian.nexusagentweb.exception.ValidationException   sessionId 不是合法 UUID / 标题为空或过长
     * @throws com.huzhijian.nexusagentweb.exception.NotFoundException     会话不存在，或不是当前用户的
     */
    void rename(String sessionId, String title);

    /**
     * 搜索会话（P3-1 补）：先匹配会话标题，再匹配消息正文，合并去重后返回。
     * <p>
     * 只搜**当前登录用户**的会话。
     *
     * @param keyword 关键词，忽略大小写；为空或超长会抛 {@code ValidationException}
     * @return 标题命中的排在前面；每个会话只出现一次
     */
    List<ChatSessionSearchVO> search(String keyword);

}
