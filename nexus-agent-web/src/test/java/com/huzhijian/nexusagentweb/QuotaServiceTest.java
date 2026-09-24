package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.QuotaServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
