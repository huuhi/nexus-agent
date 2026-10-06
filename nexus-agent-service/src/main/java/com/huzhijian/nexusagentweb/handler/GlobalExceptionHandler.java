package com.huzhijian.nexusagentweb.handler;

import com.huzhijian.nexusagentweb.exception.*;
import com.huzhijian.nexusagentweb.vo.Result;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明: 全局异常处理。
 * <p>
 * 约定：业务异常统一返回 HTTP 200 + {@code Result{code=1}}（前端依赖 code 字段判断）；
 * 未预期异常返回 HTTP 500，且**不向外暴露内部信息**，只在日志里留全量堆栈。
 * Spring 自身的标准 HTTP 异常（如 404 NoResourceFound）保留原状态码，不被兜底吞掉。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotSupportException.class)
    public Result handleNotSupport(NotSupportException ex) {
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public Result handleValidation(ValidationException ex) {
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public Result handleIOException(IOException ex) {
        log.warn("IO 异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public Result handleUnauthorized(UnauthorizedException ex) {
        return Result.error(ex.getMessage());
    }

    /**
     * 配额超限（P2-8）。与其它业务异常一致：HTTP 200 + {@code Result{code=1}}，前端按 code 判断。
     * <p>
     * 这里**不重复打日志** —— 拒绝原因与用量已在 {@code QuotaServiceImpl} 里打过 WARN。
     */
    @ExceptionHandler(QuotaExceededException.class)
    public Result handleQuotaExceeded(QuotaExceededException ex) {
//        2026-10-06：把配额快照一起带出去（fileQuota / fileUsed / fileRemaining…），
//        前端才能在被拒之前就禁用上传、显示「还能传 N 个」，而不是等撞墙才知道。
//        ⚠️ data 为 null 时 Result.error(msg) 与原来完全一致，老前端不受影响。
        return Result.error(ex.getMessage(), ex.getData());
    }

    @ExceptionHandler(ParserFileException.class)
    public Result handleParserFile(ParserFileException ex) {
        return Result.error(ex.getMessage());
    }

    /**
     * 🔴 <b>2026-10-05 补：非法状态（业务上「当前不该继续」）必须走业务异常出口，不能掉 500。</b>
     * <p>
     * 背景：`GET /api/lexiang/teams` 一直返回 500，真实原因被兜底分支吞成了
     * 「系统内部错误，请稍后重试」—— 而它实际上可能是下面任意一条**用户能自己解决**的问题：
     * <ul>
     *   <li>没保存乐享凭证 → 「尚未接入乐享知识库：请先在设置里填写 AppKey 与 AppSecret。」</li>
     *   <li>没保存过 API 配置（缺加密盐值）→ 「用户配置不完整（缺少加密盐值）…」</li>
     *   <li>AppKey 无效 / AppSecret 错 / 撞了 20 次/10 分钟限频 → {@code LexiangClient} 的中文提示</li>
     *   <li>团队列表为空（授权范围不含该成员）→ 「未获取到任何乐享团队…」</li>
     * </ul>
     * 这些全是 {@code IllegalStateException}，而这里原来没有对应处理器，
     * 于是「用户配错了」和「服务真的挂了」在前端长得一模一样，只能靠翻服务端日志才能区分。
     * <p>
     * 为什么用 {@code log.warn} 而不是 {@code log.error}：本项目的
     * {@code IllegalStateException} 一律带中文的人话消息（是给最终用户看的），
     * 属于「可预期、可自愈」的场景，不该在监控里和真 NPE 混在一起报警。
     *
     * @see #handleIllegalArgument
     */
    @ExceptionHandler(IllegalStateException.class)
    public Result handleIllegalState(IllegalStateException ex) {
        String msg = ex.getMessage();
        // ⚠️ 无参构造（new IllegalStateException()）消息是 null，直接透传会把 "null" 显示给用户
        if (msg == null || msg.isBlank()) {
            msg = "当前状态无法完成该操作，请刷新后重试";
        }
        log.warn("非法状态（业务不可继续）：{}", msg);
        return Result.error(msg);
    }

    /**
     * 参数不合法（如乐享知识库列表缺 {@code teamId}）。
     * <p>
     * 与 {@link #handleIllegalState} 同源问题：原来同样掉兜底 500。
     * 归到 4xx 语义但保持本项目的「HTTP 200 + {@code code=1}」约定，前端按 code 展示即可。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgument(IllegalArgumentException ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = "请求参数不正确";
        }
        log.warn("参数不合法：{}", msg);
        return Result.error(msg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldError() == null
                ? "参数校验失败"
                : ex.getBindingResult().getFieldError().getDefaultMessage();
        return Result.error(message);
    }

    /**
     * 上传文件超限（超过 {@code spring.servlet.multipart.max-file-size / max-request-size}）。
     * <p>
     * ⚠️ <b>2026-10-04 补：以前这里没有对应处理器，超限会掉进兜底分支变成 500，
     * 配合 Tomcat 的 swallow 行为还会断连，网关上表现为 502。</b>
     * 现在明确回 413 + 可读的中文提示（带上限数值），前端可直接展示。
     * <p>
     * 注意：这个异常在 Controller 方法**执行之前**（multipart 解析阶段）就抛出了，
     * 所以 Controller 里的大小校验根本来不及跑 —— 上限只能在配置里调。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        long limit = ex.getMaxUploadSize();
        // getMaxUploadSize() 为 -1 表示这次是「整个请求超限」而非「单文件超限」，此时不猜数值
        String limitText = limit > 0 ? (limit / 1024 / 1024) + "MB" : "";
        log.warn("上传文件超限：{}", ex.getMessage());
        String msg = limitText.isEmpty()
                ? "上传内容过大，请减少文件数量或压缩后重试"
                : "文件过大（单个上限 " + limitText + "），请压缩或分批上传";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Result.error(msg));
    }

    /**
     * 其余 multipart 解析失败（请求体不是合法 multipart、临时文件写失败等）。
     * 保留 400（客户端的问题），不要兜成 500。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result> handleMultipart(MultipartException ex) {
        log.warn("multipart 解析失败：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error("上传请求格式不正确，请用 multipart/form-data 重新提交"));
    }

    @ExceptionHandler(NotFoundException.class)
    public Result handleNotFound(NotFoundException ex) {
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public Result handlePermissionDenied(PermissionDeniedException ex) {
        return Result.error(ex.getMessage());
    }

    /**
     * 兜底：未预期的异常。
     * <p>
     * ① 对 Spring 的标准 HTTP 异常（404 / 405 / 415 等）保留原状态码，避免把 404 变成 200；
     * ② 其余一律 500 + 通用提示，绝不把堆栈或内部信息返回给调用方。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> handleUnexpected(Exception ex,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        // ⚠️ 2026-10-04：SSE 流已经开始时（Content-Type 变成 text/event-stream）
        //    不能再返回 JSON 信封 —— 没有任何 converter 能把 Result 写成 text/event-stream，
        //    写了只会二次抛 HttpMessageNotWritableException，日志刷一屏、前端还什么都收不到。
        //    这时直接往流里补一条 error 帧（契约见 docs/sse-contract.md）。
        if (isEventStream(response)) {
            writeSseErrorFrame(ex, response);
            return null;
        }
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            String detail;
            if (errorResponse.getBody() != null && errorResponse.getBody().getDetail() != null) {
                detail = errorResponse.getBody().getDetail();
            } else {
                detail = status == null ? "请求无法处理" : status.getReasonPhrase();
            }
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(Result.error(detail));
        }
        log.error("未捕获异常，请排查", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error("系统内部错误，请稍后重试或联系管理员"));
    }

    /** 判断这次请求是不是已经在跑 SSE（Content-Type 被 SseEmitter 切成 event-stream）。 */
    private boolean isEventStream(HttpServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /**
     * 往 SSE 流里补一条 error 帧（与 {@code SseResponseConverter#sendErrorEvent} 同格式）。
     * <p>
     * 只在「流已经开始、但异常又冒泡到了这里」这种意外情况下兜底 —— 正常情况下
     * 对话错误由 {@code TokenStream#onError} 处理，根本不会走到全局处理器。
     */
    private void writeSseErrorFrame(Exception ex, HttpServletResponse response) {
        String reason = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage().replaceAll("\\s+", " ").strip();
        if (reason.length() > 200) {
            reason = reason.substring(0, 200) + "...";
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("message", reason);
        try {
//            ⚠️ 必须显式指定 UTF-8：Servlet 容器（含 Tomcat）的 writer 默认按 ISO-8859-1 写，
//            而错误原因几乎总是中文 —— 不设这里，前端收到的就是一串问号。
//            （SseEmitter 那条主路径由 Spring 的 converter 负责编码，不存在这个问题。）
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("event: error\ndata: " + JSONUtil.toJsonStr(payload) + "\n\n");
            response.getWriter().flush();
        } catch (IOException ignored) {
            // 连接已经没了，没法再告诉前端任何事
        }
    }
}
