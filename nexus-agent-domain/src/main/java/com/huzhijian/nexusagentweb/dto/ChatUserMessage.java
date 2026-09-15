package com.huzhijian.nexusagentweb.dto;

import com.huzhijian.nexusagentweb.em.UserMessageType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明: 消息DTO，元数据存储文件url等数据。
 */
@Builder
public record ChatUserMessage(@NotNull(message = "消息类型不能为空！") UserMessageType type, String content, Map<String, Object> metadata) {
}
