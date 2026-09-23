package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ModelListDTO;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.vo.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value="/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@RequestBody @Valid ChatDTO chatDTO){
        SseEmitter sse=chatService.chat(chatDTO);
        return ResponseEntity.ok(sse);
    }

    /**
     * 查询某个 API 供应商下可用的模型列表。
     * <p>
     * 注意：使用 POST + 请求体，不要改回 GET query 参数——token 是用户密钥，不能出现在 URL 里。
     */
    @PostMapping("/model")
    public Result getModelList(@RequestBody @Valid ModelListDTO modelListDTO){
        List<String> models= chatService.getModelList(modelListDTO.baseUrl(), modelListDTO.token());
        return Result.ok(models);
    }
}
