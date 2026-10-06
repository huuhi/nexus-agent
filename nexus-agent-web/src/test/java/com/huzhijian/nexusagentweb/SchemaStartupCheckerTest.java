package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.SchemaStartupChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * schema 自检<b>不得阻止启动</b>（2026-10-06 回归护栏）。
 *
 * <p>背景：这个检查最初是按 {@code nexus.agent.startup.fail-fast} 判定的（默认 true），
 * 于是出现了一条荒唐的链路：
 * <pre>
 *   用户忘了执行 docs/sql/013 → 缺列 → fail-fast → 整个后端起不来
 *   → 只能退回去看 mock 夹具数据 → 反而更乱，还以为是我们把数据搞坏了
 * </pre>
 * 缺列是<b>可降级</b>的（{@code ChatMemoryServiceImpl} 已做运行期降级），
 * 所以判据应该是「能否降级」，不是「有没有列」。
 */
@DisplayName("schema 自检只 WARN，永不阻止启动（缺列是可降级的）")
class SchemaStartupCheckerTest {

    /** 少列的 DataSource 桩：information_schema 查询返回「这一列不存在」 */
    private static DataSource dataSourceWithColumn(boolean columnExists) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(any(String.class))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(columnExists, false);
        return ds;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> provider(DataSource ds) {
        ObjectProvider<DataSource> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(ds);
        return p;
    }

    @Test
    @DisplayName("列存在：正常通过，不打任何警告")
    void columnPresentPasses() throws Exception {
        SchemaStartupChecker checker = new SchemaStartupChecker(provider(dataSourceWithColumn(true)));
        assertDoesNotThrow(checker::check);
    }

    @Test
    @DisplayName("列缺失：只 WARN —— 绝不能抛异常把后端拦在起不来的状态")
    void missingColumnDoesNotBlockStartup() throws Exception {
        SchemaStartupChecker checker = new SchemaStartupChecker(provider(dataSourceWithColumn(false)));
        // 🔴 这是本测试的核心断言：抛异常 = 整个服务不可用 = 用户只能去看 mock 假数据
        assertDoesNotThrow(checker::check, "缺列可降级（运行期有兜底），不得阻止启动");
    }

    @Test
    @DisplayName("连不上数据库：同样不阻止启动（连接问题由连接池自己判定）")
    void unreachableDatabaseDoesNotBlockStartup() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new java.sql.SQLException("connection refused"));

        SchemaStartupChecker checker = new SchemaStartupChecker(provider(ds));
        assertDoesNotThrow(checker::check);
    }

    @Test
    @DisplayName("没有 DataSource（纯单测切片）：跳过，不报错")
    void noDataSourceSkips() {
        ObjectProvider<DataSource> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        assertDoesNotThrow(() -> new SchemaStartupChecker(empty).check());
    }

    /** 反射确认 {@code failFast} 依赖已移除 —— 避免有人后来又把它加回来 */
    @Test
    @DisplayName("已不再依赖 startup.fail-fast（判据是「能否降级」而非「有没有列」）")
    void noLongerReadsFailFastSwitch() {
        boolean hasFailFast = false;
        for (Method m : SchemaStartupChecker.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("failfast")) {
                hasFailFast = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertFalse(hasFailFast,
                "缺列可降级，不该再受 startup.fail-fast 控制 —— 否则又会回到「服务起不来」的老问题");
    }
}
