package com.huzhijian.nexusagentweb.converter;

import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明: SSE 输出。
 * <p>
 * 事件契约见 {@code AGENTS.md §6.2}。P2-6 起新增 {@code error} 事件：
 * 出错时先把它推给前端（带 {@code runId} = trace_id）再结束连接 ——
 * 之前出错只调 {@code completeWithError}，前端**只看到连接断了**，
 * 拿不到任何线索，也没法去日志里定位（现在可以直接用 runId 去 grep）。
 */

@Slf4j
public class SseResponseConverter {
    private final SseEmitter emitter;
    private final AtomicBoolean isFinished;
    private final StringBuilder answer;
    private final boolean isNewSession;
    private final ChatHistoryListService chatHistoryListService;
    private final String sessionId;
    private final Long userId;
    private final String message;
    /** 本次运行的 trace_id，用于把前端错误与后端日志对上 */
    private final String runId;
    /**
     * 增量缓冲（P2-12）：把逐 token 的增量合并成批次再发，避免"上千个 SSE 帧"。
     * 详见 {@link SseChunkBuffer} 的类注释。
     */
    private final SseChunkBuffer chunkBuffer;

    @Builder
    public SseResponseConverter(SseEmitter sseEmitter, boolean isNewSession, ChatHistoryListService chatHistoryListService,
                                String sessionId, Long userId, String message, String runId,
                                Integer flushMaxChars, Long flushIntervalMillis) {
        this.emitter = sseEmitter;
        this.isNewSession = isNewSession;
        this.isFinished = new AtomicBoolean(false);
        this.answer = new StringBuilder();
        this.chatHistoryListService = chatHistoryListService;
        this.sessionId = sessionId;
        this.userId = userId;
        this.message = message;
        this.runId = runId;
        this.chunkBuffer = new SseChunkBuffer(
                flushMaxChars == null ? 200 : flushMaxChars,
                flushIntervalMillis == null ? 60L : flushIntervalMillis);
    }

    public void writeThinking(PartialThinking thinking) {
        if (isFinished.get()) return;
        emit(chunkBuffer.append(MessageType.THINK.name(), thinking.text()));
    }

    public void writeContent(String token) {
        if (isFinished.get()) return;
        // 完整正文仍逐块累积：历史落库与标题生成要用完整的，只把"发送"改成批量
        answer.append(token);
        emit(chunkBuffer.append(MessageType.CONTENT.name(), token));
    }

    /** 发送一批缓冲内容；批次为空或已结束时什么都不做 */
    private void emit(SseChunkBuffer.Batch batch) {
        if (batch == null || isFinished.get()) {
            return;
        }
        MessageType type = MessageType.valueOf(batch.type());
        MessageVO chunk = MessageType.THINK == type
                ? MessageVO.builder().type(type).thinking(batch.text()).build()
                : MessageVO.builder().type(type).content(batch.text()).build();
        try {
            emitter.send(SseEmitter.event().name("message").data(chunk));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    /**
     * 把缓冲里的剩余内容发出去。
     * <p>
     * ⚠️ **必须在这些时机调用**：发送工具事件前、推送产物事件前、结束、报错 ——
     * 否则前端收到的顺序会错乱（正文插到工具结果后面），或者干脆丢掉尾部内容。
     */
    private void flushPending() {
        emit(chunkBuffer.drain());
    }

    public void writeToolRequestWithStream(PartialToolCall toolcall, PartialToolCallContext contexts) {
        String name = toolcall.name();
        String id = toolcall.id();
        String arguments = toolcall.partialArguments();

        sendRequest(name, arguments, id);

    }

    private void sendRequest(String name, String arguments, String id) {
        if (isFinished.get()) return;
        // 工具事件之前先把正文缓冲发掉，否则前端会先看到工具卡片、后看到该卡片前的正文
        flushPending();
        MessageVO.ToolRequestVO vo = MessageVO.ToolRequestVO.builder()
                .toolName(name)
                .arguments(arguments)
                .id(id)
                .build();
        MessageVO msg = MessageVO.builder()
                .type(MessageType.TOOL_EXECUTION)
                .toolRequestList(List.of(vo))
                .build();
        try {
            emitter.send(SseEmitter.event()
                    .name(MessageType.TOOL_EXECUTION.getValue())
                    .data(msg));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    public void writeToolRequest(String id,String name,String arguments) {
        sendRequest(name, arguments, id);
    }

    public void writeToolResult(ToolExecutionRequest request, boolean isError, String result) {
        if (isFinished.get()) return;
        flushPending();
        MessageVO.ToolResultVO vo = MessageVO.ToolResultVO.builder()
                .id(request.id())
                .isError(isError)
                .toolName(request.name())
                .result(result)
                .build();
        MessageVO msg = MessageVO.builder()
                .type(MessageType.TOOL_EXECUTION_RESULT)
                .toolResultVO(vo)
                .build();
        try {
            emitter.send(SseEmitter.event()
                    .name(MessageType.TOOL_EXECUTION_RESULT.getValue())
                    .data(msg));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    /**
     * 推送「交付物」事件（P2-10）：前端据此渲染下载卡片。
     * <p>
     * 事件名 {@code artifact}（小写，与 message/session_id/finish/error 一族一致），
     * data 为 {@code MessageVO{type:ARTIFACT, artifact:{id,name,url,size,extension}}}。
     * 在工具执行后由 {@code ChatServiceImpl} 调用 —— 那时产物已上传 OSS 并落库，
     * {@code id} 就是 {@code sys_file} 主键（前端可用它去重与追溯）。
     */
    public void writeArtifact(Map<String, Object> artifact) {
        if (isFinished.get()) {
            return;
        }
        flushPending();
        MessageVO msg = MessageVO.builder()
                .type(MessageType.ARTIFACT)
                .artifact(artifact)
                .build();
        try {
            emitter.send(SseEmitter.event()
                    .name(MessageType.ARTIFACT.getValue())
                    .data(msg));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    public void onError(Throwable error) {        log.error("Chat stream error runId={} session={}", runId, sessionId, error);
        sendErrorEvent(error);
        completeWithError(error);
    }

    /**
     * 出错时把 trace_id 推给前端。
     * <p>
     * 只推「对用户有意义」的信息：错误类型 + 简短原因 + runId；
     * 不推堆栈（前端读不懂，也可能泄露内部细节），定位靠 runId 去查服务端日志。
     */
    private void sendErrorEvent(Throwable error) {
        if (isFinished.get()) {
            return;
        }
        // 报错前把已生成的正文发出去：这些内容是有效的，不该因为是"错误结尾"就吞掉
        flushPending();
        String reason = error == null || error.getMessage() == null
                ? (error == null ? "未知错误" : error.getClass().getSimpleName())
                : error.getMessage().strip().replaceAll("\\s+", " ");
        if (reason.length() > 200) {
            reason = reason.substring(0, 200) + "...";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", MessageType.ERROR.getValue());
        payload.put("runId", runId);
        payload.put("message", reason);
        payload.put("hint", "请把 runId 提供给开发者，可在服务端日志中检索 \"RUN runId=" + runId + "\" 定位本次运行");
        try {
            emitter.send(SseEmitter.event().name("error").data(payload));
        } catch (Exception e) {
            // 发送失败不能再抛：连接可能已经断了，这里只记日志
            log.debug("推送 error 事件失败（连接可能已断开）：runId={} {}", runId, e.getMessage());
        }
    }

    /**
     * 正常完成
     */
    public void finish() {
        if (isFinished.get()) return;
        try {
            // 结束前把缓冲里剩下的正文发出去，否则回复的尾部会丢
            flushPending();
            // 新会话时生成标题并且返回新的会话ID
            if (isNewSession) {
                emitter.send(SseEmitter.event().name("session_id").data(sessionId));
                chatHistoryListService.createTitle(sessionId, message, answer.toString(), userId);
            }
            emitter.send(SseEmitter.event().name("finish").data("DONE"));
            emitter.complete();
            isFinished.set(true);
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    private void completeWithError(Throwable error) {
        if (isFinished.getAndSet(true)) return;
        emitter.completeWithError(error);
    }

    // 暴露 isFinished 供外部检查，但通常不需要
    public boolean isFinished() {
        return isFinished.get();
    }

    public String getFullAnswer() {
        return answer.toString();
    }

}
