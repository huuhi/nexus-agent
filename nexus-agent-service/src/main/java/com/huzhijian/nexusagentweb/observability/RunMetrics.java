package com.huzhijian.nexusagentweb.observability;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 一次对话运行（Run）的指标累加器。
 * <p>
 * 解决根因 R6「不可观测」：原来一次对话花了多少 token、调了哪些工具、耗时多久、
 * 用了哪个模型，全都查不到 —— 用户只能凭感觉猜成本。
 * <p>
 * <b>为什么是可变的</b>：它跨多个流式回调（`onToolExecuted` / `onCompleteResponse` / `onError`）
 * 累加数据，而 {@code RunContext} 是 record（不可变），不适合承担这个职责。
 * 本对象只在**一次 Run 内**使用并随 Run 结束而丢弃，不跨 Run 复用。
 * <p>
 * <b>线程安全</b>：回调可能来自不同线程（流式响应线程），故累加字段用原子类型；
 * 工具序列用 {@link ArrayDeque} 但仅在 {@code summarize} 时读取，
 * 且同一 Run 内工具是串行执行的（LangChain4j 的工具调用是顺序的）。
 */
public class RunMetrics {

    /** 工具序列最多保留多少步，避免长 Run（几十次工具调用）把日志撑爆 */
    private static final int MAX_TOOL_SEQUENCE = 30;

    private final String runId;
    private final String sessionId;
    private final Long userId;
    private final long startedAt = System.currentTimeMillis();

    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger toolFailures = new AtomicInteger();
    private final Deque<String> toolSequence = new ArrayDeque<>();
    private final AtomicInteger truncatedToolCount = new AtomicInteger();

    private volatile String modelName;
    private volatile TokenUsage tokenUsage;
    private volatile Throwable error;

    public RunMetrics(String runId, String sessionId, Long userId) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.userId = userId;
    }

    /**
     * 记录一次工具执行结果。
     *
     * @param failed 是否失败（来自 {@code ToolExecution.hasFailed()}；注意被
     *               {@code ToolCallGuard} 拦截的调用**不算失败** —— 它返回的是正常结果，
     *               只是内容是「重复调用」提示）
     */
    public void recordToolExecuted(String toolName, boolean failed) {
        toolCalls.incrementAndGet();
        if (failed) {
            toolFailures.incrementAndGet();
        }
        synchronized (toolSequence) {
            if (toolSequence.size() >= MAX_TOOL_SEQUENCE) {
                toolSequence.pollFirst();
                truncatedToolCount.incrementAndGet();
            }
            toolSequence.addLast(failed ? toolName + "(failed)" : toolName);
        }
    }

    /** 记录模型返回（含 token 用量与真实模型名） */
    public void recordResponse(ChatResponse response) {
        if (response == null) {
            return;
        }
        if (response.modelName() != null && !response.modelName().isBlank()) {
            this.modelName = response.modelName();
        }
        if (response.tokenUsage() != null) {
            this.tokenUsage = response.tokenUsage();
        }
    }

    public void recordError(Throwable throwable) {
        this.error = throwable;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public String runId() {
        return runId;
    }

    public String sessionId() {
        return sessionId;
    }

    public Long userId() {
        return userId;
    }

    public String modelName() {
        return modelName;
    }

    public TokenUsage tokenUsage() {
        return tokenUsage;
    }

    public Throwable error() {
        return error;
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int toolFailures() {
        return toolFailures.get();
    }

    /** 工具名序列（失败项带 {@code (failed)} 后缀），超过上限时前面会被截断 */
    public String toolSequenceText() {
        synchronized (toolSequence) {
            String seq = String.join(",", toolSequence);
            int truncated = truncatedToolCount.get();
            return truncated > 0 ? "...(+" + truncated + ")," + seq : seq;
        }
    }

    /**
     * 汇总成**单行结构化文本**（键值对，便于 grep 与后续接统计/落库）。
     * <p>
     * 用英文键而不是中文：日志会被 grep / awk / 采集器解析，中文键做分隔符很别扭。
     *
     * @param feeText 已格式化好的费用文本（如 {@code ¥0.0034}）；无法估算时传 null
     */
    public String summarize(String feeText) {
        TokenUsage usage = tokenUsage;
        StringBuilder sb = new StringBuilder(256);
        sb.append("RUN runId=").append(runId)
                .append(" session=").append(sessionId)
                .append(" user=").append(userId)
                .append(" model=").append(modelName == null ? "unknown" : modelName)
                .append(" cost=").append(String.format("%.1fs", elapsedMillis() / 1000.0))
                .append(" tokens=");
        if (usage == null) {
            // 不是所有厂商都返回用量，缺了就说缺了，不要编造 0
            sb.append("unavailable");
        } else {
            sb.append(nullSafe(usage.inputTokenCount())).append('/')
                    .append(nullSafe(usage.outputTokenCount())).append('/')
                    .append(nullSafe(usage.totalTokenCount())).append("(in/out/total)");
        }
        sb.append(" fee=").append(feeText == null ? "unpriced" : feeText)
                .append(" tools=").append(toolCalls.get());
        if (toolFailures.get() > 0) {
            sb.append("(failed=").append(toolFailures.get()).append(')');
        }
        sb.append(" seq=[").append(toolSequenceText()).append(']');
        sb.append(" result=").append(error == null ? "OK" : "ERROR");
        if (error != null) {
            sb.append(" err=").append(error.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static int nullSafe(Integer value) {
        return value == null ? -1 : value;
    }
}
