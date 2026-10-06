package com.huzhijian.nexusagentweb.exception;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 用户 token / 文件配额超限（P2-8）。
 * <p>
 * 单独一个异常类型而不是复用 ValidationException：配额超限是**业务规则拒绝**，
 * 不是参数错误 —— 排查问题时要能一眼区分（日志、监控告警条件都会不同）。
 * <p>
 * <b>2026-10-06 补 data 载荷</b>：以前只带一句文案，前端只能弹个 toast 然后让用户
 * 「再试一次」—— 可他根本不知道自己离上限还有多远。下次直接撞墙时才告知，
 * 体验上等于「突然不让用了」。现在把当时的配额快照带出来
 * （{@code fileQuota} / {@code fileUsed} / {@code fileRemaining} 等），
 * 前端可以提前禁掉上传按钮并显示「还能传 N 个」。
 * <p>
 * ⚠️ HTTP 状态码仍是 200 + {@code code=1}（见 {@code GlobalExceptionHandler}），
 * 与项目里所有业务失败保持一致，前端按 {@code code !== 0} 统一弹 msg。
 */
public class QuotaExceededException extends RuntimeException {

    /** 随异常带出的配额快照；null 表示这次拒绝与配额无关（如未登录） */
    private final transient Map<String, Object> data;

    public QuotaExceededException(String message) {
        this(message, null);
    }

    public QuotaExceededException(String message, Map<String, Object> data) {
        super(message);
        this.data = data;
    }

    /**
     * 配额快照，供 {@code GlobalExceptionHandler} 填进 {@code Result.data}。
     * <p>
     * 为 null 时前端拿不到结构化信息，退回只弹 msg 的老行为（向前兼容）。
     */
    public Map<String, Object> getData() {
        return data;
    }
}

