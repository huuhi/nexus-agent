package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ModelListDTO;
import com.huzhijian.nexusagentweb.model.SystemModelRegistry;
import com.huzhijian.nexusagentweb.service.ChatService;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
@RestController
@RequestMapping("/api/chat")
@Tag(name = "对话", description = "发起对话（SSE 流式）与模型列表查询")
public class ChatController {

    private final ChatService chatService;
    private final SystemModelRegistry systemModelRegistry;

    public ChatController(ChatService chatService, SystemModelRegistry systemModelRegistry) {
        this.chatService = chatService;
        this.systemModelRegistry = systemModelRegistry;
    }

    /**
     * ⚠️ 这是 SSE 接口：返回的是 `text/event-stream`，**不是**一次性 JSON。
     * 帧结构（信封 `{seq, runId, event, data}`、7 种事件、断线重连约定）
     * 见仓库内 `docs/sse-contract.md` —— 那是手写契约，也是权威版本；
     * OpenAPI/Swagger 描述不了"一条连接里按序到达的多种事件"，这里只登记入口参数。
     */
    @Operation(
            summary = "发起对话（SSE 流式）",
            description = """
                    返回 `text/event-stream`。浏览器原生 `EventSource` **用不了**
                    （它不能自定义请求头，而本项目鉴权靠 `token` 头），
                    需用 `fetch` + `ReadableStream` 自行解析 —— 解析示例见 `docs/sse-contract.md`。

                    事件序列：`run`（首帧，带 runId/sessionId）→ `message` / `tool_execution` /
                    `tool_execution_result` / `artifact` … → `finish` 或 `error`。
                    """)
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
    @Operation(summary = "查询 API 供应商下可用的模型列表",
            description = "用 POST + 请求体而非 GET：token 是用户密钥，不能出现在 URL / 浏览器历史 / 访问日志里。")
    @PostMapping("/model")
    public Result getModelList(@RequestBody @Valid ModelListDTO modelListDTO){
        List<String> models= chatService.getModelList(modelListDTO.baseUrl(), modelListDTO.token());
        return Result.ok(models);
    }

    /**
     * 列出**系统内置模型**（没配自带 Key 的用户可选的那批）。
     * <p>
     * 2026-10-03：系统模型从"yml 里唯一一个"改成可配列表（多供应商），
     * 前端需要一个地方知道现在有哪些可选，才能在模型选择器里列出来。
     * 返回的是展示信息，**不含 apiKey**（那是服务端配置，不能下发给前端）。
     */
    @Operation(summary = "系统内置模型列表",
            description = "返回 nexus.agent.system-models 配置的模型：id / 名称 / 模型名 / 是否支持图片 / 上下文窗口 / 最大输出。不含密钥。列表为空表示未启用（此时后端用 langchain4j 的单一默认模型）。")
    @GetMapping("/models")
    public Result getSystemModels(){
        return Result.ok(systemModelRegistry.getDefinitions().stream()
                .map(def -> Map.of(
                        "id", def.getId(),
                        "name", def.getName() == null ? def.getModelName() : def.getName(),
                        "modelName", def.getModelName(),
                        "vision", Boolean.TRUE.equals(def.getVision())))
                .toList());
    }
}
