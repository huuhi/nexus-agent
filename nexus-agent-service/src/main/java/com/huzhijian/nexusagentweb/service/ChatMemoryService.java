package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.vo.MessageVO;

import java.util.List;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Service
* @createDate 2026-04-16 20:02:49
*/
public interface ChatMemoryService extends IService<ChatHistory> {
    List<ChatHistory> getByMemoryId(Object memory);

    /**
     * 按会话 ID + 用户 ID 读取历史。
     * <p>
     * 对话链路读记忆必须用这个：sessionId 由客户端传入，只按 sessionId 查
     * 等于「知道别人的 sessionId 就能读到别人的聊天记录」。
     */
    List<ChatHistory> getByMemoryIdAndUserId(Object memory, Long userId);

    /**
     * 取会话最后一条消息的原始 JSON（锚点），用于增量写入时定位新增部分。
     *
     * @return JSON 文本；会话为空时返回 null
     */
    String getLastMessageJson(Object sessionId, Long userId);

    /**
     * 取会话最近 limit 条消息的原始 JSON（时间正序），用于锚点失配时的兜底去重。
     */
    List<String> getRecentMessageJson(Object sessionId, Long userId, int limit);

    void delByMemoryId(Object memoryId);
    void insertBatch(List<ChatHistory> list,Long userId);


//    List<MessageVO> getHistory(String sessionId);

    int getCountBySessionID(String sessionId);

    List<MessageVO> getHistoryBySessionId(String sessionId);

}
