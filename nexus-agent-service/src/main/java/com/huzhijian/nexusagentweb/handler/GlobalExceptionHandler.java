package com.huzhijian.nexusagentweb.handler;

import com.huzhijian.nexusagentweb.exception.*;
import com.huzhijian.nexusagentweb.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

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
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldError() == null
                ? "参数校验失败"
                : ex.getBindingResult().getFieldError().getDefaultMessage();
        return Result.error(message);
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
    public ResponseEntity<Result> handleUnexpected(Exception ex) {
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
}
