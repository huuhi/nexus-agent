package com.huzhijian.nexusagentweb.converter;

import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明:
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

    @Builder
    public SseResponseConverter(SseEmitter sseEmitter, boolean isNewSession, ChatHistoryListService chatHistoryListService, String sessionId, Long userId, String message) {
        this.emitter = sseEmitter;
        this.isNewSession = isNewSession;
        this.isFinished = new AtomicBoolean(false);
        this.answer = new StringBuilder();
        this.chatHistoryListService = chatHistoryListService;
        this.sessionId = sessionId;
        this.userId = userId;
        this.message = message;
    }

    public void writeThinking(PartialThinking thinking) {
        if (isFinished.get()) return;
        try {
            MessageVO chunk = MessageVO.builder()
                    .type(MessageType.THINK)
                    .thinking(thinking.text())
                    .build();
            emitter.send(SseEmitter.event().name("message").data(chunk));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    public void writeContent(String token) {
        if (isFinished.get()) return;
        answer.append(token);
        try {
            MessageVO chunk = MessageVO.builder()
                    .type(MessageType.CONTENT)
                    .content(token)
                    .build();
            emitter.send(SseEmitter.event().name("message").data(chunk));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    public void writeToolRequest(PartialToolCall request) {
        if (isFinished.get()) return;
        MessageVO.ToolRequestVO vo = MessageVO.ToolRequestVO.builder()
                .toolName(request.name())
                .arguments(request.partialArguments())
                .id(request.id())
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

    public void writeToolResult(ToolExecutionRequest request, boolean isError, String result) {
        if (isFinished.get()) return;
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

    public void onError(Throwable error) {
        log.error("Chat stream error", error);
        completeWithError(error);
    }

    /**
     * 正常完成
     */
    public void finish() {
        if (isFinished.get()) return;
        try {
            // 新会话时生成标题
            if (isNewSession) {
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
