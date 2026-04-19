package com.huzhijian.nexusagentweb.dto;

import com.huzhijian.nexusagentweb.em.UserMessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明: 消息DTO，元数据存储文件url等数据。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatUserMessage {
    private UserMessageType type;
    private String content;
    private Map<String,Object> metadata;
}
