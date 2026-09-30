package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.em.QuotaPeriod;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.QuotaServiceImpl;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * token 配额（P2-8）的单元测试。
 * <p>
 * 判定逻辑用纯函数 {@code evaluate} 覆盖边界；拦截行为用 mock 的 UserMapper 覆盖，
 * 不连数据库。
 */
class QuotaServiceTest {

    private QuotaServiceImpl service(UserMapper mapper, boolean enabled) {
        AgentProperties props = new AgentProperties();
        props.getQuota().setEnabled(enabled);
        return new QuotaServiceImpl(mapper, props);
    }

    /** 带全局周期配置的构造（周期重置相关用例用） */
    private QuotaServiceImpl service(UserMapper mapper, QuotaPeriod globalPeriod) {
        AgentProperties props = new AgentProperties();
        props.getQuota().setEnabled(true);
        props.getQuota().setPeriod(globalPeriod);
        return new QuotaServiceImpl(mapper, props);
    }

    // ------------------------------------------------------------------
    //  判定规则（纯函数）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未设配额（null / 0 / 负数）一律放行 —— 老用户行为不变")
    void unlimitedWhenQuotaAbsent() {
        assertTrue(QuotaServiceImpl.evaluate(null, 999_999L).allowed());
        assertTrue(QuotaServiceImpl.evaluate(0L, 999_999L).allowed());
        assertTrue(QuotaServiceImpl.evaluate(-1L, 999_999L).allowed());
    }

    @Test
    @DisplayName("已用量小于配额时放行")
    void allowedWhenBelowQuota() {
        QuotaServiceImpl.Decision decision = QuotaServiceImpl.evaluate(1000L, 999L);

        assertTrue(decision.allowed());
        assertEquals(1000L, decision.quota());
        assertEquals(999L, decision.used());
    }

    @Test
    @DisplayName("已用量正好等于配额时拒绝（边界：用完即拦）")
    void rejectedWhenExactlyAtQuota() {
        assertFalse(QuotaServiceImpl.evaluate(1000L, 1000L).allowed());
    }

    @Test
    @DisplayName("已用量超过配额时拒绝")
    void rejectedWhenAboveQuota() {
        assertFalse(QuotaServiceImpl.evaluate(1000L, 1200L).allowed());
    }

    @Test
    @DisplayName("已用量为 null 视为 0（新用户没有记账记录）")
    void nullUsedTreatedAsZero() {
        QuotaServiceImpl.Decision decision = QuotaServiceImpl.evaluate(100L, null);

        assertTrue(decision.allowed());
        assertEquals(0L, decision.used());
    }

    // ------------------------------------------------------------------
    //  拦截行为（mock mapper）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("超限时抛出 QuotaExceededException，且提示里带已用量与上限")
    void throwsWhenExceeded() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenQuota(1000L).tokenUsed(1500L).build());

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> service(mapper, true).assertWithinQuota(7L));

        assertTrue(ex.getMessage().contains("1500"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1000"), ex.getMessage());
    }

    @Test
    @DisplayName("未超限时放行")
    void passesWhenWithinQuota() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenQuota(1000L).tokenUsed(10L).build());

        assertDoesNotThrow(() -> service(mapper, true).assertWithinQuota(7L));
    }

    @Test
    @DisplayName("配额校验关闭时**不查库**（省一次查询）")
    void skippedWhenDisabled() {
        UserMapper mapper = mock(UserMapper.class);

        assertDoesNotThrow(() -> service(mapper, false).assertWithinQuota(7L));
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("用户不存在时不报「配额超限」（避免误导排查方向）")
    void missingUserIsNotAQuotaError() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(null);

        assertDoesNotThrow(() -> service(mapper, true).assertWithinQuota(7L));
    }

    @Test
    @DisplayName("查库失败时降级放行（典型场景：库里还没执行 003，缺 token_quota 列）")
    void queryFailureDegradesToAllow() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L))
                .thenThrow(new RuntimeException("column \"token_quota\" does not exist"));

        // 配额校验不是主流程的一部分，不能因为建列遗漏就把整次对话打挂
        assertDoesNotThrow(() -> service(mapper, true).assertWithinQuota(7L));
    }

    // ------------------------------------------------------------------
    //  用量记账
    // ------------------------------------------------------------------

    @Test
    @DisplayName("记账：把用量交给 mapper 做原子累加")
    void recordUsageDelegatesToMapper() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.addTokenUsage(eq(7L), eq(1234L))).thenReturn(1);

        service(mapper, true).recordUsage(7L, 1234);

        verify(mapper).addTokenUsage(7L, 1234L);
    }

    @Test
    @DisplayName("记账失败不能让对话失败（吞掉异常）")
    void recordUsageSwallowsException() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.addTokenUsage(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("数据库连接断了"));

        assertDoesNotThrow(() -> service(mapper, true).recordUsage(7L, 100));
    }

    @Test
    @DisplayName("用量为 null / 非正数时不写库（不把「未知」记成 0）")
    void recordUsageSkipsEmptyValues() {
        UserMapper mapper = mock(UserMapper.class);
        QuotaServiceImpl service = service(mapper, true);

        service.recordUsage(7L, null);
        service.recordUsage(7L, 0);
        service.recordUsage(null, 100);

        verify(mapper, never()).addTokenUsage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("判定结果对象带全量信息，便于日志与排查")
    void decisionCarriesContext() {
        QuotaServiceImpl.Decision decision = QuotaServiceImpl.evaluate(500L, 600L);

        assertNotNull(decision.quota());
        assertNotNull(decision.used());
        assertFalse(decision.allowed());
    }

    // ------------------------------------------------------------------
    //  周期重置（P2-8 遗留，docs/sql/007）
    // ------------------------------------------------------------------

    /** 造一个「配额 100、已用 1000」的超限用户 */
    private static User exhaustedUser(String period) {
        return User.builder()
                .id(7L).tokenQuota(100L).tokenUsed(1000L)
                .tokenPeriod(period)
                .build();
    }

    @Test
    @DisplayName("周期为 NONE 时不触发任何重置（与 003 行为完全一致）")
    void nonePeriodNeverResets() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("NONE"));

        assertThrows(QuotaExceededException.class,
                () -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
        verify(mapper, never()).resetQuotaPeriod(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("跨周期时清零：重置命中（rows>0）后原本超限的用户被放行")
    void resetFreesExhaustedUser() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("DAILY"));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(() -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
        verify(mapper).resetQuotaPeriod(eq(7L), eq(LocalDate.now().atStartOfDay()));
    }

    @Test
    @DisplayName("未跨周期（rows=0）时仍按累计用量判定 —— 不能因为「试了一下」就放行")
    void noResetKeepsAccumulatedUsage() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("DAILY"));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(0);

        assertThrows(QuotaExceededException.class,
                () -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
    }

    @Test
    @DisplayName("重置失败（典型：007 没执行）时降级为「不重置」，而不是放行")
    void resetFailureStillCountsAccumulated() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("DAILY"));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("column \"token_period\" does not exist"));

        // 比直接放行更保守：宁可误拦，也不能让配额形同虚设
        assertThrows(QuotaExceededException.class,
                () -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
    }

    @Test
    @DisplayName("用户未单独配周期时，用全局默认周期")
    void globalPeriodAppliesWhenUserUnset() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser(null));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(() -> service(mapper, QuotaPeriod.MONTHLY).assertWithinQuota(7L));
        verify(mapper).resetQuotaPeriod(eq(7L),
                eq(LocalDate.now().withDayOfMonth(1).atStartOfDay()));
    }

    @Test
    @DisplayName("用户自己的周期**覆盖**全局配置")
    void userPeriodOverridesGlobal() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("DAILY"));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(1);

        assertDoesNotThrow(() -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
        // 用的是 DAILY 的起点（当天 00:00），不是全局 NONE
        verify(mapper).resetQuotaPeriod(eq(7L), eq(LocalDate.now().atStartOfDay()));
    }

    @Test
    @DisplayName("库里周期值非法时按 NONE 处理（与 QuotaPeriod.of 的容错一致）")
    void invalidUserPeriodDegradesToNone() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("weekly"));

        assertThrows(QuotaExceededException.class,
                () -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));
        verify(mapper, never()).resetQuotaPeriod(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("超限提示里带上周期说明，方便用户知道什么时候能恢复")
    void messageMentionsPeriod() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(exhaustedUser("MONTHLY"));
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(0);

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> service(mapper, QuotaPeriod.NONE).assertWithinQuota(7L));

        assertTrue(ex.getMessage().contains("每月"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    //  用量查询（GET /api/user/quota）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("查询返回配额 / 已用 / 剩余，剩余不为负")
    void getQuotaReturnsUsage() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenQuota(1000L).tokenUsed(300L).tokenPeriod("NONE").build());

        QuotaVO vo = service(mapper, QuotaPeriod.NONE).getQuota(7L);

        assertFalse(vo.isDegraded());
        assertFalse(vo.isUnlimited());
        assertEquals(1000L, vo.getQuota());
        assertEquals(300L, vo.getUsed());
        assertEquals(700L, vo.getRemaining());
        assertEquals("NONE", vo.getPeriod());
        assertNull(vo.getPeriodStart(), "NONE 没有周期起点");
    }

    @Test
    @DisplayName("用超配额时剩余量钳到 0，不出现负数")
    void getQuotaClampsRemaining() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenQuota(100L).tokenUsed(150L).tokenPeriod("NONE").build());

        assertEquals(0L, service(mapper, QuotaPeriod.NONE).getQuota(7L).getRemaining());
    }

    @Test
    @DisplayName("未配额度视为不限量：quota / remaining 为 null，used 仍有统计意义")
    void getQuotaUnlimited() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenUsed(8888L).tokenPeriod("NONE").build());

        QuotaVO vo = service(mapper, QuotaPeriod.NONE).getQuota(7L);

        assertTrue(vo.isUnlimited());
        assertNull(vo.getQuota());
        assertNull(vo.getRemaining());
        assertEquals(8888L, vo.getUsed());
    }

    @Test
    @DisplayName("周期用户查询时也会顺手刷新，避免显示「上一周期遗留的用量」")
    void getQuotaTriggersLazyReset() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(User.builder()
                .id(7L).tokenQuota(1000L).tokenUsed(1000L).tokenPeriod("DAILY").build());
        when(mapper.resetQuotaPeriod(eq(7L), any(LocalDateTime.class))).thenReturn(1);

        QuotaVO vo = service(mapper, QuotaPeriod.NONE).getQuota(7L);

        assertEquals(0L, vo.getUsed(), "跨周期后展示的用量应为 0");
        assertEquals(1000L, vo.getRemaining());
        assertEquals(LocalDate.now().atStartOfDay(), vo.getPeriodStart());
    }

    @Test
    @DisplayName("查库失败时返回 degraded，而不是把异常抛给前端")
    void getQuotaDegradesOnError() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L))
                .thenThrow(new RuntimeException("column \"token_quota\" does not exist"));

        QuotaVO vo = service(mapper, QuotaPeriod.NONE).getQuota(7L);

        assertTrue(vo.isDegraded(), "没跑 003/007 的环境也要能打开设置页");
    }

    @Test
    @DisplayName("用户 ID 为空（未登录 / 上下文丢失）时返回 degraded")
    void getQuotaDegradesForAnonymous() {
        QuotaVO vo = service(mock(UserMapper.class), QuotaPeriod.NONE).getQuota(null);

        assertTrue(vo.isDegraded());
    }

    @Test
    @DisplayName("用户不存在时返回 degraded")
    void getQuotaDegradesForMissingUser() {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(7L)).thenReturn(null);

        assertTrue(service(mapper, QuotaPeriod.NONE).getQuota(7L).isDegraded());
    }
}
