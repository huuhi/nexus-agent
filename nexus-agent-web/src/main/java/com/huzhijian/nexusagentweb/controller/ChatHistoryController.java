package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.Result;
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
    private final ChatHistoryListService  chatHistoryListService;

    public ChatHistoryController(ChatMemoryService chatMemoryService, ChatHistoryListService chatHistoryListService) {
        this.chatMemoryService = chatMemoryService;
        this.chatHistoryListService = chatHistoryListService;
    }

    @GetMapping("/{sessionId}")
    public Result getMessage(@PathVariable String sessionId){
        List<MessageVO> history=chatMemoryService.getHistoryBySessionId(sessionId);
        return Result.ok(history);
    }

    @DeleteMapping
    public Result deleteMessage(@RequestParam String sessionId){
        chatHistoryListService.deleteSession(sessionId);
        return Result.ok();
    }
    @GetMapping
    public Result getHistoryList(){
        List<ChatHistoryList> list=chatHistoryListService.getList();
        return Result.ok(list);
    }
}
