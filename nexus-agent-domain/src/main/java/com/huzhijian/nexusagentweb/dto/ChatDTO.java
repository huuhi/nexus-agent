package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public record ChatDTO(@NotNull(message = "发送的消息不能为空！") List<ChatUserMessage> messages,
                      String sessionId,
                      List<String> skills,
                      List<Long> MCPs,
                      ModelDTO model,
                      /**
                       * 是否启用乐享知识库检索。
                       * <p>
                       * ⚠️ 原先还有一个 {@code enableRag}（本地 pgvector 知识库），已于 2026-10-04
                       * 随本地知识库一起下线 —— 现在知识库检索只有乐享这一条路。
                       */
                      boolean enableLexiangRag) {
}
