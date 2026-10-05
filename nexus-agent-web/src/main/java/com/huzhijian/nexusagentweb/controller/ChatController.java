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

import java.util.LinkedHashMap;
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
     * 查询某个 API 配置下可用的模型列表。
     * <p>
     * 🔴 <b>2026-10-05 契约变更：只传 {@code configId}，不再传 baseUrl / token。</b>
     * 旧契约要前端回传密钥，但前端手上只有后端打码后的展示值（{@code sk****3t5d}），
     * 拿去调厂商必然 401 —— 这不是前端传错，是契约本身错了。
     * 对话 / 乐享 RAG / MCP 三处一直都是后端自己查库解密，现在这一处也对齐了。
     * <p>
     * 仍然用 POST + 请求体（不改成 GET）：虽然不再传密钥，但 configId 属于用户配置标识，
     * 放 URL 里会进浏览器历史与 access log。
     * <p>
     * 厂商不支持 {@code /v1/models} 或 Key 不对时，后端会**降级**返回该配置里
     * 用户已保存的模型名，<b>不会 500</b>，所以可能返回空列表。
     */
    @Operation(summary = "查询 API 配置下可用的模型列表",
            description = "只传 configId（用户配置 id）。baseUrl 与 API Key 由后端从 user_config 查库并解密，前端不接触密钥。"
                    + "厂商不支持 /v1/models 时降级返回该配置里已保存的模型名，可能返回空列表，不会报错。")
    @PostMapping("/model")
    public Result getModelList(@RequestBody @Valid ModelListDTO modelListDTO){
        List<String> models = chatService.getModelList(modelListDTO.configId());
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
        return Result.ok(systemModelRegistry.getEntries().stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", e.id());
                    item.put("name", e.name());
                    item.put("modelName", e.modelName());
                    item.put("vision", e.capabilities().vision());
                    item.put("contextWindow", e.capabilities().contextWindow());
                    item.put("maxOutputTokens", e.capabilities().maxOutputTokens());
//                    供应商信息：前端可按它分组展示（"DeepSeek / 阿里云百炼 / ..."）
                    if (e.providerId() != null) {
                        item.put("providerId", e.providerId());
                    }
                    if (e.providerName() != null) {
                        item.put("providerName", e.providerName());
                    }
                    return item;
                })
                .toList());
    }
}
