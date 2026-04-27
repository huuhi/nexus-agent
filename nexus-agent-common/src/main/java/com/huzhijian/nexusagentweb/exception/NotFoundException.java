package com.huzhijian.nexusagentweb.exception;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/27
 * 说明: 资源未发现异常
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
