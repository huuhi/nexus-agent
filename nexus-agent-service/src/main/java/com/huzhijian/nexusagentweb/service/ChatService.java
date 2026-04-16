package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.dto.ChatDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatService {
    SseEmitter chat(ChatDTO chatDTO);
}
