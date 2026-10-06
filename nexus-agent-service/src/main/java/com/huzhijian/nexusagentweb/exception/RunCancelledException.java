package com.huzhijian.nexusagentweb.exception;

/**
 * 「用户主动叫停本次运行」的内部信号（2026-10-06 新增）。
 *
 * <h3>它为什么必须是一个异常</h3>
 * <p>
 * langchain4j 的 {@code TokenStream} <b>没有 cancel/close 方法</b> ——
 * 唯一能真正停下来��办法是让消费它的回调链抛异常，
 * {@code DefaultTokenStream} 内部的 {@code Spliterator} 循环会因此终止，
 * 上游的 HTTP 流随之被取消（连接关闭，供应商那边也不再继续生成）。
 * <p>
 * 所以「停止」在本项目里的实现代价是：<b>异常会走到 {@code onError}</b>，
 * 而 langchain4j <b>只在 {@code onCompleteResponse} 时才把消息写进记忆</b> ——
 * 也就是说，光抛异常会让用户已经看到的半截回答<b>刷新后消失</b>。
 * 补救见 {@code ChatServiceImpl} 的处理：识别到本异常时，按 runId 幂等补写一条 AI 消息。
 *
 * <h3>为什么不用 QuotaExceededException 那类业务异常</h3>
 * <p>
 * 它<b>不是</b>错误，而是用户的正常意图。必须在 {@code onError} 里与真正的错误分流：
 * 本异常 → 发 {@code stopped} 事件（前端把光标停住、去掉「思考中」）；
 * 其他异常 → 发 {@code error} 事件。
 *
 * <h3>为什么没注册到 GlobalExceptionHandler</h3>
 * <p>
 * 它<b>不会</b>逃逸到 HTTP 层 —— 产生它的位置（流式回调）与捕获它的位置
 * （同一个 {@code TokenStream} 的 {@code onError}）都在应用内部。
 * 注册它反而会让「万一逃逸」变成一条静默的 200 响应，掩盖真正的编码错误。
 * 这与「新增业务异常必须注册」是两条不同的规则：那条针对的是<b>会被抛出到 HTTP 层</b>的异常。
 *
 * @author 胡志坚
 */
public class RunCancelledException extends RuntimeException {

    /**
     * @param runId 被叫停的运行 id（写进 message 只为日志可读，堆栈不会给前端）
     */
    public RunCancelledException(String runId) {
        super("用户主动停止了本次生成：runId=" + runId, null, false, false);
    }
}
