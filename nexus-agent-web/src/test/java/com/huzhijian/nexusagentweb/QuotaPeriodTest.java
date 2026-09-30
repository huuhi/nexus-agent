package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.em.QuotaPeriod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配额重置周期枚举（P2-8 遗留）的单元测试。
 * <p>
 * 重点覆盖两件事：
 * <ol>
 *   <li>{@code of()} 的**容错** —— 库里没执行 {@code docs/sql/007} 时字段为 null，
 *       必须退回 NONE 而不是抛异常（否则一次改表遗漏会让所有人没法对话）；</li>
 *   <li>{@code startOf()} 的**周期起点计算** —— 这是重置判定唯一的事实来源，
 *       算错一天就会让所有人的配额提前/延后一天刷新。</li>
 * </ol>
 */
class QuotaPeriodTest {

    // ------------------------------------------------------------------
    //  容错解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null / 空串 / 全空白 → NONE（未执行 007 时的兜底）")
    void ofBlankIsNone() {
        assertEquals(QuotaPeriod.NONE, QuotaPeriod.of(null));
        assertEquals(QuotaPeriod.NONE, QuotaPeriod.of(""));
        assertEquals(QuotaPeriod.NONE, QuotaPeriod.of("   "));
    }

    @Test
    @DisplayName("未知值 → NONE（库里被手工写脏也不至于崩）")
    void ofUnknownIsNone() {
        assertEquals(QuotaPeriod.NONE, QuotaPeriod.of("WEEKLY"));
        assertEquals(QuotaPeriod.NONE, QuotaPeriod.of("daily-x"));
    }

    @Test
    @DisplayName("大小写与首尾空格都容错")
    void ofIsCaseAndSpaceInsensitive() {
        assertEquals(QuotaPeriod.DAILY, QuotaPeriod.of("daily"));
        assertEquals(QuotaPeriod.DAILY, QuotaPeriod.of(" Daily "));
        assertEquals(QuotaPeriod.MONTHLY, QuotaPeriod.of("MONTHLY"));
    }

    // ------------------------------------------------------------------
    //  周期起点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NONE 没有周期概念，startOf 返回 null")
    void noneHasNoStart() {
        assertNull(QuotaPeriod.NONE.startOf(LocalDate.of(2026, 3, 15)));
        assertFalse(QuotaPeriod.NONE.resets());
        assertTrue(QuotaPeriod.DAILY.resets());
        assertTrue(QuotaPeriod.MONTHLY.resets());
    }

    @Test
    @DisplayName("DAILY 的周期起点 = 当天 00:00")
    void dailyStartIsMidnight() {
        LocalDateTime start = QuotaPeriod.DAILY.startOf(LocalDate.of(2026, 3, 15));

        assertEquals(LocalDateTime.of(2026, 3, 15, 0, 0), start);
    }

    @Test
    @DisplayName("MONTHLY 的周期起点 = 当月 1 号 00:00（与传入日无关）")
    void monthlyStartIsFirstDay() {
        assertEquals(LocalDateTime.of(2026, 3, 1, 0, 0),
                QuotaPeriod.MONTHLY.startOf(LocalDate.of(2026, 3, 31)));
        assertEquals(LocalDateTime.of(2026, 3, 1, 0, 0),
                QuotaPeriod.MONTHLY.startOf(LocalDate.of(2026, 3, 1)));
    }

    @Test
    @DisplayName("跨月边界：3/31 之后的新周期起点必须大于 3/1（否则永不重置）")
    void monthlyRolloverAdvances() {
        LocalDateTime march = QuotaPeriod.MONTHLY.startOf(LocalDate.of(2026, 3, 31));
        LocalDateTime april = QuotaPeriod.MONTHLY.startOf(LocalDate.of(2026, 4, 1));

        assertTrue(april.isAfter(march), "4 月的周期起点必须晚于 3 月，否则 WHERE 条件永远不成立");
        assertEquals(LocalDateTime.of(2026, 4, 1, 0, 0), april);
    }
}
