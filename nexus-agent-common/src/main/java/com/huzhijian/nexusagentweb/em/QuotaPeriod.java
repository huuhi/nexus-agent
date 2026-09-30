package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * token 配额的**重置周期**（P2-8 遗留）。
 * <p>
 * 存库时用的是 {@code name()}（varchar），读取用 {@link #of} 容错：
 * 库里的值为 null / 空 / 未知时一律按 {@link #NONE} 处理 ——
 * 这样即使 `docs/sql/007` 没执行（列不存在 → 实体字段为 null），
 * 行为也退回 003 的「累计不重置」，而不是抛异常。
 */
@Getter
public enum QuotaPeriod {

    /** 不重置：`token_used` 累计只增不减（003 的原始语义，也是默认值） */
    NONE("NONE"),
    /** 每日重置：周期起点 = 当天 00:00 */
    DAILY("DAILY"),
    /** 每月重置：周期起点 = 当月 1 号 00:00 */
    MONTHLY("MONTHLY"),
    ;

    private final String value;

    QuotaPeriod(String value) {
        this.value = value;
    }

    /** 容错解析：null / 空 / 未知 → {@link #NONE} */
    public static QuotaPeriod of(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        try {
            return QuotaPeriod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }

    /**
     * 计算给定日期所处的周期起点（**纯函数**，便于单测）。
     *
     * @return {@link #NONE} 返回 {@code null}（没有周期概念）
     */
    public LocalDateTime startOf(LocalDate date) {
        return switch (this) {
            case NONE -> null;
            case DAILY -> date.atStartOfDay();
            case MONTHLY -> date.withDayOfMonth(1).atStartOfDay();
        };
    }

    /** 是否需要周期重置 */
    public boolean resets() {
        return this != NONE;
    }
}
