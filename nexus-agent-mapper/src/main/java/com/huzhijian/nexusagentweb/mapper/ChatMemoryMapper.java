package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
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
     * 对话链路读历史专用：只取<b>最近</b> {@code limit} 条（2026-10-06 新增）。
     * <p>
     * 与 {@link #getAllByMemoryIdAndUserId} 的区别只有"条数上限"，其余（越权过滤、
     * 时间正序）完全一致。用途差异：
     * <ul>
     *   <li>本方法：喂给 {@code TokenWindowChatMemory}，它本来就会按窗口淘汰旧消息，
     *       所以没必要把几百轮以前的历史先传回来再丢掉 —— 那部分传输与反序列化成本
     *       是白付的（表现为越聊越慢）。</li>
     *   <li>{@code getAllByMemoryIdAndUserId}：需要完整历史的场景（导出/统计）继续全量。</li>
     * </ul>
     *
     * @param limit 最多取多少条；{@code <= 0} 表示不限制（等同全量）
     */
    List<ChatHistory> getRecentForChat(@Param("sessionId") Object sessionId,
                                       @Param("userId") Long userId,
                                       @Param("limit") int limit);

    /**
     * 记忆加载专用：同 {@link #getRecentForChat}，但**排除被「重新生成」替代掉的回答**（2026-10-06）。
     * <p>
     * 为什么要分两个查询：历史接口要返回**全部**版本（前端做 n/n 切换），
     * 而模型上下文只能要当前生效的那一版。
     * 被替代的只是「不参与模型上下文」，**不是不存在**。
     *
     * @param limit 最多取多少条；<b>{@code <= 0} 表示不限制</b>
     */
    List<ChatHistory> getActiveForChat(@Param("sessionId") Object sessionId,
                                       @Param("userId") Long userId,
                                       @Param("limit") int limit);

    /**
     * 重新生成后，把「比 {@code sinceId} 更新的、尚未被替代的」AI 回答标记为被 {@code newId} 替代。
     * <p>
     * 语义是「自 sinceId 之后的所有现行版本一并出局」，而不是「只标记某一条」——
     * 用户可能先在 1/2 上重新生成、过一会儿又在 1/1 上重新生成，后者必须让前者也出局。
     * <p>
     * ⚠️ 只影响 {@code type='AI'} 的行：用户提问不能被标记掉，
     * 否则模型下一轮就不知道「在回答哪个问题」了。
     *
     * @return 被标记的行数
     */
    int markSupersededSince(@Param("sessionId") Object sessionId,
                            @Param("userId") Long userId,
                            @Param("sinceId") Long sinceId,
                            @Param("newId") Long newId,
                            @Param("excludeRunId") String excludeRunId);

    /**
     * 按 runId 找本次运行写入的<b>第一条 AI 回答</b>的 id（2026-10-06 重新生成用）。
     * <p>
     * 它就是「新版本的 id」，要写进被替代行的 {@code superseded_by}。
     * 一次运行只会有一条 AI 回答（多轮工具调用也只有一条最终回答），
     * 所以 {@code order by id limit 1} 是确定的。
     *
     * @return 没找到返回 {@code null}（调用方按「不是重新生成」处理，不报错）
     */
    Long findFirstAiMessageIdOfRun(@Param("sessionId") Object sessionId,
                                   @Param("userId") Long userId,
                                   @Param("runId") String runId);

    /**
     * 该会话下、属于这次运行的历史行是否已存在（2026-10-06 新增）。
     * <p>
     * 用途是「用户叫停」时补写已生成内容的<b>幂等守卫</b>：补写前先问一句，
     * 避免重复插入同一轮的行 —— 重复行会让下一轮增量写入的锚点定位错位，
     * 进而把整段历史重写一遍（那比少写一条严重得多）。
     * <p>
     * ⚠️ 必须带 {@code user_id}，理由同其他对外查询：{@code sessionId} 是客户端可见的。
     */
    boolean existsByRunId(@Param("sessionId") Object sessionId,
                          @Param("userId") Long userId,
                          @Param("runId") String runId);

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

    /**
     * 会话搜索：按关键词匹配消息正文，返回**消息粒度**的命中（P3-1 补）。
     * <p>
     * ⚠️ 必须带 {@code user_id}：sessionId 是客户端能看到的 UUID，
     * 少了这一条就等于「改个参数能搜到别人的聊天记录」。
     *
     * @param userId  当前登录用户
     * @param pattern 已转义的 LIKE 模式（形如 {@code %关键词%}），由 Service 层拼好
     * @param limit   最多扫多少条命中消息（防大结果集，聚合在 Service 层做）
     */
    List<ChatMemorySearchHit> searchHits(@Param("userId") Long userId,
                                         @Param("pattern") String pattern,
                                         @Param("limit") int limit);

    @Select("select count(session_id) from chat_memory where session_id=#{sessionId}::uuid")
    int getCountByMemoryId(String sessionId);
}




