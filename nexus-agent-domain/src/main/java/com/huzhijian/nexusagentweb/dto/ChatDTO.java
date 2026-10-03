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
                      /** 是否启用本地知识库（pgvector）检索 */
                      boolean enableRag,
                      /** 是否启用乐享知识库检索；与 enableRag 可同时为 true（两个库都会检索） */
                      boolean enableLexiangRag) {
}
