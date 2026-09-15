package com.huzhijian.nexusagentweb.exception;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/6
 * 说明:
 */
public class PermissionDeniedException extends RuntimeException {
    public PermissionDeniedException(String message) {
        super(message);
    }
}
