package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@Data
public class ChatDTO {
    @NotBlank(message = "发送的消息不能为空！")
    private String message;
    private String sessionId;
    private List<String> skills;
    private List<String> MCPs;
    private String model;
    private String knowledgeBase;
}
