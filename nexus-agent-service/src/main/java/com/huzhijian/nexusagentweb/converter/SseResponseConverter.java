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
    /**
     * 连接是否已断开（超时 / 客户端关网页 / 网络断）。
     * <p>
     * ⚠️ **与 {@link #isFinished} 是两回事**，这是 2026-10-03 的关键修正：
     * <ul>
     *   <li>以前：onTimeout → onError → isFinished=true → 后续所有产出被丢弃，
     *       但 TokenStream **还在跑** —— 钱在烧、结果全扔，是最坏的组合；</li>
     *   <li>现在：连接断开只置 {@code disconnected}，TokenStream 继续跑完，
     *       消息照常写进 chat_memory、标题照常生成 —— 用户刷新页面就能看到完整回复。</li>
     * </ul>
     * "任务结束"（finish / 任务内报错）仍由 isFinished 表达。
     */
    private volatile boolean disconnected;
    /** 心跳任务：长时间不吐字（工具执行中）时防止中间代理（Nginx 默认读超时 60s）掐断连接 */
    private java.util.concurrent.ScheduledFuture<?> heartbeatTask;

    /** 共享心跳调度池（单线程、daemon）：任务很轻（一次 send），cancel 后线程复用，避免每请求泄漏线程 */
    private static final java.util.concurrent.ScheduledExecutorService HEARTBEAT_POOL =
            java.util.concurrent.Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

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
        startHeartbeat();
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
     * 每 15 秒发一个 SSE 注释帧（{@code :ping}）。
     * <p>
     * 目的：agent 干活期间（工具执行、模型思考）可能**几分钟没有任何事件**，
     * 中间的 Nginx 等反代默认 60s 读超时就会把连接掐掉 —— 前端表现为"不动了"。
     * 注释帧对 {@code EventSource} 完全透明（不触发任何回调），也不影响事件契约。
     */
    private void startHeartbeat() {
        heartbeatTask = HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            if (disconnected || isFinished.get()) {
                cancelHeartbeat();
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
//                发不出去说明连接已经没了：标记断开，让任务继续跑完落库
                log.info("心跳发送失败，标记连接断开（任务继续）：runId={}", runId);
                disconnect("心跳发送失败");
            }
        }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    /**
     * 连接断开（超时 / 客户端关网页 / 心跳失败）。
     * <p>
     * 只停止**发送**，不终止任务：TokenStream 继续跑完，
     * 消息照常落库、标题照常生成 —— 用户刷新页面就能看到完整回复。
     * <p>
     * ⚠️ 与 {@link #onError(Throwable)} 的区别：onError 是**任务本身**出错（要尽快收尾），
     * disconnect 只是**传输通道**没了（任务照常）。
     */
    public void disconnect(String reason) {
        if (disconnected) {
            return;
        }
        disconnected = true;
        cancelHeartbeat();
        log.info("SSE 连接断开（任务继续在后台跑完，结果会落库）：runId={} session={} 原因={}",
                runId, sessionId, reason);
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
        if (isFinished.get() || disconnected) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getSeq()))
                    .name(event.getEvent())
                    .data(event));
        } catch (IOException e) {
            // 发送失败 = 连接没了，不是任务出错：标记断开让任务继续跑完落库
            disconnect("发送失败: " + e.getMessage());
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

    public void onError(Throwable error) {
        log.error("Chat stream error runId={} session={}", runId, sessionId, error);
        sendErrorEvent(error);
        safeComplete();
    }

    /**
     * 用户主动叫停（2026-10-06 新增，配套 {@code POST /api/chat/stop}）。
     * <p>
     * <b>刻意不发 {@code error} 事件</b>：叫停是用户的正常意图，不是失败。
     * 发错误弹窗会让用户以为「出错了」，而实际上他想要的就是停下来。
     * <p>
     * 收尾照常做：把缓冲里剩下的正文发出去（否则最后几个字会丢）、停心跳、关流。
     * <b>已生成内容的落库不在这里做</b> —— langchain4j 只在正常完成时写记忆，
     * 被中断时那条 AI 消息缺失，由 {@code ChatServiceImpl} 按 runId 补写
     * （见 {@code RunCancelledException} 的类注释）。
     *
     * @param partialChars 已生成正文的字符数，告诉前端「回答没写完」
     */
    public void writeStopped(int partialChars) {
        if (isFinished.get()) {
            return;
        }
        try {
            // 缓冲里没发出去的尾部必须先补发，否则用户看到的回答会凭空少一截
            flushPending();
        } catch (Exception e) {
            log.warn("叫停时补发尾部正文失败（已发内容不受影响）：runId={} 原因={}", runId, e.getMessage());
        }
        send(SseEventType.STOPPED, Map.of(
                "reason", "用户停止了本次生成",
                "partial", partialChars));
        safeComplete();
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
     * 正常完成（或连接断开后任务跑完）。
     * <p>
     * 即使连接早已断开（disconnected=true）也要走到这里：
     * 标题生成、（经 TokenMemoryStore 的）消息落库都依赖这个收尾 ——
     * 用户刷新页面时看到的就是这些数据。发送部分会因 disconnected 自动跳过。
     */
    public void finish() {
        if (isFinished.get()) return;
        try {
            // 结束前把缓冲里剩下的正文发出去，否则回复的尾部会丢（连接已断则自动跳过）
            flushPending();
            // 新会话时生成标题（sessionId 已在首帧 run 事件里给过前端，这里不再重复下发）
            if (isNewSession) {
                chatHistoryListService.createTitle(sessionId, message, answer.toString(), userId);
            }
            send(SseEventType.FINISH, Map.of("status", "DONE"));
            emitter.complete();
            isFinished.set(true);
        } catch (Exception e) {
            // 收尾本身出错（标题生成失败等）：正文已经发完了，记录一下再关流，
            // 不要把异常继续往外抛 —— 它只会变成容器里的一条无主错误
            log.warn("SSE 收尾失败（流已关闭，不影响已发内容）：runId={} 原因={}", runId, e.getMessage());
            safeComplete();
        } finally {
            cancelHeartbeat();
        }
    }

    /**
     * 收尾：标记结束 + 关闭流。
     * <p>
     * 🔴 <b>2026-10-04：这里原来是 {@code emitter.completeWithError(error)}，必须改掉。</b>
     * 那个方法会让 Servlet 容器对这个异步请求走一次 **error dispatch**（转发到 {@code /error}），
     * 而 SSE 响应的 Content-Type 已经是 {@code text/event-stream} ——
     * 没有任何 HttpMessageConverter 能把 {@code /error} 的 Map（或我们的 {@code Result}）
     * 写成这个类型，于是二次抛出
     * {@code HttpMessageNotWritableException: No converter for [...] with preset Content-Type
     * 'text/event-stream'}，接着全局异常处理器试图补一个 {@code Result} 又失败，
     * 日志里刷出一长串堆栈，前端反而收不到干净的错误。
     * <p>
     * 错误内容已经在 {@link #sendErrorEvent(Throwable)} 里作为 {@code error} 事件发给前端了，
     * 这里只需要把流正常关掉。
     */
    private void safeComplete() {
        if (isFinished.getAndSet(true)) {
            return;
        }
        cancelHeartbeat();
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接可能早已超时 / 已被容器回收：收尾到此为止，别让收尾本身再抛异常
        }
    }

    // 暴露 isFinished 供外部检查，但通常不需要
    public boolean isFinished() {
        return isFinished.get();
    }

    public String getFullAnswer() {
        return answer.toString();
    }

}
