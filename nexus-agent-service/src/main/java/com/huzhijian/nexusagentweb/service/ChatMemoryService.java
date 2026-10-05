package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
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
     * 对话链路读历史专用：只取<b>最近</b> {@code limit} 条（2026-10-06 新增）。
     * <p>
     * 用途是喂给记忆窗口 —— 窗口本来就会淘汰旧消息，所以没必要先把整个会话
     * 传回来再丢掉。详见 {@code AgentProperties.Memory#maxHistoryMessages}。
     *
     * @param limit 最多取多少条；<b>{@code <= 0} 表示不限制</b>（等同
     *              {@link #getByMemoryIdAndUserId}，SQL 层不接受 0 或负数）
     */
    List<ChatHistory> getRecentForChat(Object sessionId, Long userId, int limit);

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

    /**
     * 按关键词搜索消息正文，返回**消息粒度**的命中（P3-1 补，会话搜索的底层查询）。
     * <p>
     * ⚠️ 必须带 userId：会话 ID 是客户端可见的 UUID，只传关键词等于能搜到别人的聊天记录。
     * 聚合、排序、拼片段都在 {@code ChatHistoryListServiceImpl#search} 里做。
     *
     * @param pattern 已转义的 LIKE 模式（形如 {@code %关键词%}），由调用方拼好；
     *                SQL 里带 {@code escape '\'}，所以 {@code %} / {@code _} / {@code \} 必须先转义
     * @param limit   最多返回多少条命中消息
     */
    List<ChatMemorySearchHit> searchHits(Long userId, String pattern, int limit);

    List<MessageVO> getHistoryBySessionId(String sessionId);

}
