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
     * 记忆加载专用：同 {@link #getRecentForChat}，但排除被「重新生成」替代掉的回答（2026-10-06）。
     * <p>
     * 🔴 重复查库为什么必须分两个方法：历史接口要返回全部版本（前端 n/n 切换），
     * 模型上下文只能要当前生效的版本。
     */
    List<ChatHistory> getActiveForChat(Object sessionId, Long userId, int limit);

    /**
     * 重新生成落库后，把更早的现行 AI 回答标记为被替代（2026-10-06）。
     * <p>
     * 这就是让「n/n 版本切换」真正成立的那一步：不标记的话，被替代的旧回答仍在模型上下文里，
     * 用户切回 1/2 接着聊，模型记得的仍是 2/2 的内容。
     *
     * @param sinceId 用户点「重新生成」时所在的那条回答的 id（从历史接口的 {@code id} 拿）
     * @param newId   本次新写的那条回答的 id
     * @return 被标记的行数；0 表示「这不是重新生成」或「没有更新的版本需要让位」
     */
    int markSupersededSince(Object sessionId, Long userId, Long sinceId, Long newId, String excludeRunId);

    /**
     * 按 runId 找本次运行写入的第一条 AI 回答的 id（2026-10-06 重新生成用）。
     *
     * @return 没找到返回 {@code null}（调用方按「不是重新生成」处理，不当错误）
     */
    Long findFirstAiMessageIdOfRun(Object sessionId, Long userId, String runId);

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

    /**
     * 该会话下、属于这次运行的历史行是否已存在（2026-10-06 新增）。
     * <p>
     * 「用户叫停」时要把已生成的那半截回答补写进库，补写前用它做幂等守卫 ——
     * 重复插入同一轮的行会让下一轮增量写入的锚点错位，把整段历史重写一遍。
     *
     * @param runId 本次运行的 trace_id（老数据该列为 null，不会匹配）
     */
    boolean existsByRunId(Object sessionId, Long userId, String runId);


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
