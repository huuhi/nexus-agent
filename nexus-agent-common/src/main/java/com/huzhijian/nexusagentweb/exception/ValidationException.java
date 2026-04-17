package com.huzhijian.nexusagentweb.exception;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明: 参数校验失败，比如文件为空，必要参数错误等
 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
