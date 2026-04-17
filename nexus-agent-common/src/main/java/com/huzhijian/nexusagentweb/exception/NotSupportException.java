package com.huzhijian.nexusagentweb.exception;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明: 不支持错误，比如：文件类型不支持等
 */
public class NotSupportException extends RuntimeException {
    public NotSupportException(String message) {
        super(message);
    }
}
