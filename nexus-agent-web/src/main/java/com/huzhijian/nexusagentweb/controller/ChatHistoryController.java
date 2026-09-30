package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/18
 * 说明:
 */
@RestController
@RequestMapping("/api/history")
@Tag(name = "会话与历史", description = "会话列表、某会话的历史消息、删除会话")
public class ChatHistoryController {

    private final ChatMemoryService chatMemoryService;
    private final ChatHistoryListService  chatHistoryListService;

    public ChatHistoryController(ChatMemoryService chatMemoryService, ChatHistoryListService chatHistoryListService) {
        this.chatMemoryService = chatMemoryService;
        this.chatHistoryListService = chatHistoryListService;
    }

    @Operation(summary = "按会话 ID 取历史消息",
            description = "会话归属已做校验：不是本人的会话返回空/拒绝（越权防护在 Service 层）。")
    @GetMapping("/{sessionId}")
    public Result getMessage(@PathVariable String sessionId){
        List<MessageVO> history=chatMemoryService.getHistoryBySessionId(sessionId);
        return Result.ok(history);
    }

    @Operation(summary = "删除会话（含其历史消息）")
    @DeleteMapping
    public Result deleteMessage(@RequestParam String sessionId){
        chatHistoryListService.deleteSession(sessionId);
        return Result.ok();
    }

    @Operation(summary = "当前用户的会话列表")
    @GetMapping
    public Result getHistoryList(){
        List<ChatHistoryList> list=chatHistoryListService.getList();
        return Result.ok(list);
    }
}
