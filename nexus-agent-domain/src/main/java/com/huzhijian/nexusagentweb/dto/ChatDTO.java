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
                      List<String> MCPs,
                      ModelDTO model, String knowledgeBase, boolean enableRag) {
}
