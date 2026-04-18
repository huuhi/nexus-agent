package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/18
 * 说明:
 */
@RestController
@RequestMapping("/history")
public class ChatHistoryController {

    private final ChatMemoryService chatMemoryService;

    public ChatHistoryController(ChatMemoryService chatMemoryService) {
        this.chatMemoryService = chatMemoryService;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<List<MessageVO>> getMessage(@PathVariable String sessionId){
        List<MessageVO> history=chatMemoryService.getHistoryBySessionId(sessionId);
        return ResponseEntity.ok(history);
    }
}
