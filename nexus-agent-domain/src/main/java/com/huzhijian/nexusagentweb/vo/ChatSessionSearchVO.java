package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 会话搜索结果（P3-1 补）。
 * <p>
 * 一个会话只出现**一次**：标题命中与内容命中会合并，标题命中优先（{@link #matchType} 为
 * {@code TITLE}），前端据此决定要不要展示 snippet。
 * <p>
 * 排序规则（Service 层保证）：标题命中整体排在内容命中前面；组内按最近活跃时间倒序。
 */
@Data
@Builder
public class ChatSessionSearchVO {

    /** 会话 ID（UUID 字符串） */
    private String sessionId;

    /**
     * 会话标题。
     * <p>
     * 可能为空：标题是异步生成的（{@code ChatHistoryListServiceImpl#createTitle}），
     * 生成失败或还没跑完时 {@code chat_history_list} 里没有对应行。
     * 前端请兜底显示「未命名会话」。
     */
    private String title;

    /** 会话最近更新时间 */
    private Date updateTime;

    /**
     * 命中位置：{@code TITLE} = 标题命中，{@code CONTENT} = 消息正文命中。
     * 用字符串而不是枚举，是为了让前端 diff / mock 更省事（与 SSE 事件名保持同一种风格）。
     */
    private String matchType;

    /**
     * 命中片段：正文命中时给出**包含关键词的一段文本**（前后各留若干字符），
     * 前端可以高亮其中的关键词。标题命中时为 {@code null}（标题本身就是命中内容）。
     */
    private String snippet;

    /** 该会话内命中的消息条数（标题命中时为 0） */
    private Integer hitCount;
}
