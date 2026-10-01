package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.dto.RenameSessionDTO;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@Tag(name = "会话与历史", description = "会话列表、某会话的历史消息、重命名、搜索、删除会话")
public class ChatHistoryController {

    private final ChatMemoryService chatMemoryService;
    private final ChatHistoryListService  chatHistoryListService;

    public ChatHistoryController(ChatMemoryService chatMemoryService, ChatHistoryListService chatHistoryListService) {
        this.chatMemoryService = chatMemoryService;
        this.chatHistoryListService = chatHistoryListService;
    }

    /**
     * ⚠️ 这个字面量路径**必须**声明在 {@code /{sessionId}} 之前。
     * 两者都能匹配 {@code GET /api/history/search}：Spring 会挑更具体的那个，
     * 但把顺序写明白可以避免以后有人调整代码顺序时踩坑（曾经有项目因此把 search 当成 sessionId 解析）。
     */
    @Operation(summary = "搜索会话",
            description = "先匹配会话标题，再匹配消息正文，合并去重后返回；只搜当前登录用户的会话。"
                    + "结果里 matchType=TITLE 表示标题命中，CONTENT 表示正文命中（带命中片段 snippet）。")
    @GetMapping("/search")
    public Result search(@RequestParam String keyword){
        return Result.ok(chatHistoryListService.search(keyword));
    }

    @Operation(summary = "按会话 ID 取历史消息",
            description = "会话归属已做校验：不是本人的会话返回空/拒绝（越权防护在 Service 层）。")
    @GetMapping("/{sessionId}")
    public Result getMessage(@PathVariable String sessionId){
        List<MessageVO> history=chatMemoryService.getHistoryBySessionId(sessionId);
        return Result.ok(history);
    }

    @Operation(summary = "重命名会话",
            description = "只能改自己的会话：不是本人的会话返回「会话不存在或不属于当前用户」。")
    @PutMapping("/{sessionId}/title")
    public Result rename(@PathVariable String sessionId, @Valid @RequestBody RenameSessionDTO dto){
        chatHistoryListService.rename(sessionId, dto.title());
        return Result.ok();
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
