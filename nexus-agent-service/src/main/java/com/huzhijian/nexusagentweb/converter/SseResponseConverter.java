package com.huzhijian.nexusagentweb.converter;

import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.em.SseEventType;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.SseEvent;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明: SSE 输出。
 * <p>
 * <b>契约 v2（P2-5）</b>：所有事件统一走 {@link SseEvent} 信封（{@code seq} + {@code runId} + {@code event} + {@code data}），
 * 事件名全部小写、取自 {@link SseEventType}。权威契约在 **`docs/sse-contract.md`**（`AGENTS.md §6.2` 只留索引）。
 * <p>
 * v1 → v2 的破坏性变更：
 * <ol>
 *   <li>事件名 `TOOL_EXECUTION`/`TOOL_EXECUTION_RESULT` → **`tool_execution`/`tool_execution_result`**（改小写）；</li>
 *   <li>所有 data 套信封（多了 `seq`/`runId`/`event`，原载荷移到 `data` 字段）；</li>
 *   <li>`session_id` 事件**取消**，`sessionId` 并入首帧的 **`run`** 事件（且从"流结束时"提前到"第一帧"）；</li>
 *   <li>`finish` 的 data 由字符串 `"DONE"` 改为 `{"status":"DONE"}`。</li>
 * </ol>
 *
 * @see #dispatch(SseEvent)
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
     * 帧序号（P2-5）：从 1 开始单调递增。
     * 用 {@link AtomicLong} 是因为流式回调跑在线程池里，不保证单线程。
     */
    private final AtomicLong seq;
    /**
     * 增量缓冲（P2-12）：把逐 token 的增量合并成批次再发，避免"上千个 SSE 帧"。
     * 详见 {@link SseChunkBuffer} 的类注释。
     */
    private final SseChunkBuffer chunkBuffer;
    /**
     * `run` 首帧是否已发出（P2-5）。
     * <p>
     * 用"惰性补发"而不是在构造器里发：构造器里发帧会踩到 Java 的初始化顺序
     * （子类字段在 {@code super(...)} 之后才初始化，若子类覆写了发送方法就会 NPE），
     * 而且构造器做 I/O 本身也不是好设计。这里保证 **第一个事件之前一定有 run 帧**，
     * 调用方不可能忘。
     */
    private final AtomicBoolean runSent;

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
        this.seq = new AtomicLong(0);
        this.runSent = new AtomicBoolean(false);
        this.chunkBuffer = new SseChunkBuffer(
                flushMaxChars == null ? 200 : flushMaxChars,
                flushIntervalMillis == null ? 60L : flushIntervalMillis);
    }

    /**
     * 发出首帧 `run`（{@code seq=1}），带 `runId` 与 `sessionId`。**幂等**（只发一次）。
     * <p>
     * 由 {@code ChatServiceImpl} 在建好 writer 后立刻调用；即便忘了调，
     * {@link #send} 也会兜底补发 —— 保证 `run` **永远是第一帧**。
     * <p>
     * 刻意不在构造器里发：构造器做 I/O 会踩到 Java 初始化顺序
     * （子类字段在 {@code super(...)} 之后才初始化，若子类覆写了发送方法就 NPE）。
     */
    public void start() {
        if (!runSent.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("isNewSession", isNewSession);
        dispatch(SseEvent.builder()
                .seq(seq.incrementAndGet())
                .runId(runId)
                .event(SseEventType.RUN.getValue())
                .data(data)
                .build());
    }

    /**
     * 唯一的发送出口（P2-5）。
     * <p>
     * ① 统一编号与信封；② 把 seq 写进 SSE 原生 `id:` 字段 —— 浏览器 `EventSource`
     * 会自动记住它，断线重连时作为 `Last-Event-ID` 回传，前端据此知道"断在第几帧"。
     * <p>
     * 声明为 `protected` 是为了单测能直接断言「事件名 / seq / runId / data」，
     * 不必去 mock `SseEmitter` 的内部机制（它把事件转成 `Set<DataWithMediaType>`，外部很难读回来）。
     */
    protected void dispatch(SseEvent event) {
        if (isFinished.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getSeq()))
                    .name(event.getEvent())
                    .data(event));
        } catch (IOException e) {
            completeWithError(e);
        }
    }

    /** 编号 + 封装 + 发送。发任何事件前先确保 `run` 首帧已经发出（{@link #start} 幂等） */
    private void send(SseEventType type, Object data) {
        if (type != SseEventType.RUN) {
            start();
        }
        dispatch(SseEvent.builder()
                .seq(seq.incrementAndGet())
                .runId(runId)
                .event(type.getValue())
                .data(data)
                .build());
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
        send(SseEventType.MESSAGE, chunk);
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
        send(SseEventType.TOOL_EXECUTION, msg);
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
        send(SseEventType.TOOL_EXECUTION_RESULT, msg);
    }

    /**
     * 推送「交付物」事件（P2-10）：前端据此渲染下载卡片。
     * <p>
     * 事件名 {@code artifact}，data 为 {@code MessageVO{type:ARTIFACT, artifact:{id,name,url,size,extension}}}。
     * 在工具执行后由 {@code ChatServiceImpl} 调用 —— 那时产物已上传 OSS 并落库，
     * {@code id} 就是 `sys_file` 主键（前端可用它去重与追溯）。
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
        send(SseEventType.ARTIFACT, msg);
    }

    public void onError(Throwable error) {        log.error("Chat stream error runId={} session={}", runId, sessionId, error);
        sendErrorEvent(error);
        completeWithError(error);
    }

    /**
     * 出错时把 trace_id 推给前端。
     * <p>
     * 只推「对用户有意义」的信息：错误类型 + 简短原因 + runId（在信封里）；
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
        payload.put("message", reason);
        payload.put("hint", "请把 runId 提供给开发者，可在服务端日志中检索 \"RUN runId=" + runId + "\" 定位本次运行");
        send(SseEventType.ERROR, payload);
    }

    /**
     * 正常完成
     */
    public void finish() {
        if (isFinished.get()) return;
        try {
            // 结束前把缓冲里剩下的正文发出去，否则回复的尾部会丢
            flushPending();
            // 新会话时生成标题（sessionId 已在首帧 run 事件里给过前端，这里不再重复下发）
            if (isNewSession) {
                chatHistoryListService.createTitle(sessionId, message, answer.toString(), userId);
            }
            send(SseEventType.FINISH, Map.of("status", "DONE"));
            emitter.complete();
            isFinished.set(true);
        } catch (Exception e) {
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
