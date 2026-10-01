package com.huzhijian.nexusagentweb.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话搜索的**一条命中消息**（P3-1 补）。
 * <p>
 * 这是 Mapper 直接返回的行类型，不是对外 VO：
 * SQL 返回的是「消息粒度」的命中，而接口要的是「会话粒度」的结果，
 * 中间的分组/合并/排序在 Service 层做（Java 里做聚合比在 SQL 里写窗口函数好测）。
 * <p>
 * 只有 {@code ChatMemoryMapper#searchHits} 用它。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMemorySearchHit {

    /** 会话 ID（SQL 里已 {@code ::text}，避免 uuid ↔ String 的类型处理器问题） */
    private String sessionId;

    /**
     * 命中消息的**纯文本正文**（SQL 已经把 jsonb 里的 text 抽平了）。
     * 可能为空字符串：某些消息（如纯工具调用请求）没有正文。
     */
    private String snippet;

    /** 该消息的创建时间，用于「按最近命中排序」 */
    private Date createAt;
}
