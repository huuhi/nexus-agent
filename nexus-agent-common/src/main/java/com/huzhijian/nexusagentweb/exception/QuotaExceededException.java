package com.huzhijian.nexusagentweb.exception;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 用户 token 配额超限（P2-8）。
 * <p>
 * 单独一个异常类型而不是复用 ValidationException：配额超限是**业务规则拒绝**，
 * 不是参数错误 —— 排查问题时要能一眼区分（日志、监控告警条件都会不同）。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
