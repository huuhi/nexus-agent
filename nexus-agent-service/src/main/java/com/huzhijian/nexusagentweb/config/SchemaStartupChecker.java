package com.huzhijian.nexusagentweb.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时检查**数据库列**是否存在（2026-10-06 新增）。
 *
 * <h3>为什么配置自检里没有它</h3>
 * <p>
 * {@link StartupConfigValidator} 查的是 {@code Environment}（yml / 环境变量），
 * 而「这张表有没有这一列」是 schema 层面的事，只能问数据库。
 * 两者不重叠：yml 全部配对、{@code fail-fast} 也通过了，照样可能少执行了一个迁移脚本。
 *
 * <h3>为什么值得单独做（而不是靠文档提醒）</h3>
 * <p>
 * 少执行 {@code docs/sql/013} 的后果是<b>所有历史查询直接报错</b> ——
 * {@code SELECT ... superseded_by} 在列不存在时会抛
 * {@code org.postgresql.util.PSQLException: column ... does not exist}，
 * 于是「发消息」与「拉历史」两条最核心的链路一起挂，首页直接白屏。
 * <p>
 * 这种故障<b>从日志上看只是一条 SQL 报错</b>，很容易被误判成「代码写错了」而回滚版本。
 * 放在启动期报出来，指向就非常明确：<b>少跑了一个 SQL</b>。
 *
 * <h3>失败语义：只 WARN，永不阻止启动（2026-10-06 调整）</h3>
 * <p>
 * <b>这里刻意不受 {@code nexus.agent.startup.fail-fast} 控制</b>，与
 * {@link StartupConfigValidator} 不同。原因：
 * <ul>
 *   <li>{@code fail-fast} 管的是「<b>必需</b>配置缺失」—— 缺了服务<b>根本用不了</b>，
 *       早失败好过白屏；</li>
 *   <li>{@code superseded_by} 缺失是<b>可降级</b>的：{@code ChatMemoryServiceImpl}
 *       已经做了运行期降级（改查不带该列的 SQL），聊天与历史完全正常，
 *       只是「重新生成的版本切换」不生效。</li>
 * </ul>
 * 为一个可降级的功能让<b>整个后端起不来</b>，代价过大 —— 而且这个版本最初就是
 * 按 fail-fast 实现的，结果是：<b>用户忘了执行 013 → 服务直接不可用</b>，
 * 只能退回去看 mock 夹具数据，反而更乱。
 * <p>
 * 改判据：<b>能否降级</b>，而不是「有没有列」。
 *
 * @author 胡志坚
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaStartupChecker {

    /**
     * 用 {@code ObjectProvider} 而不是直接注入：这个检查<b>不是应用可用性的前提</b>，
     * 不该因为某个容器里没有 DataSource（比如纯单测切片）就让启动失败。
     */
    private final ObjectProvider<DataSource> dataSourceProvider;

    /**
     * 必须存在的列。
     * <p>
     * 只登记「缺了会立刻引发运行时异常」的列。像 {@code run_id} 这种列即使缺了，
     * 查询也只返回 null（老数据按「归属不明」降级），不列入。
     * <p>
     * ⚠️ 用 record 而不是 {@code List<String[]>}：后者传给 {@code List.of} 时
     * 会被当成 varargs <b>展开</b>成 {@code List<String>}（编译器直接报类型不兼容），
     * 是个很容易踩且很难一眼看出的坑。
     */
    private record RequiredColumn(String table, String column) {
    }

    private static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("chat_memory", "superseded_by"));

    @PostConstruct
    public void check() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.debug("未找到 DataSource，跳过数据库 schema 自检");
            return;
        }
        List<String> missing = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            for (RequiredColumn required : REQUIRED_COLUMNS) {
                if (!columnExists(conn, required.table(), required.column())) {
                    missing.add(required.table() + "." + required.column());
                }
            }
        } catch (Exception e) {
//            🔴 连不上数据库不是「缺列」，只提示不阻断：连接问题由连接池自己 fail-fast，
//            这里重复阻断只会把真正的错误（连接串/账号）盖掉，还容易让人误以为是 schema 问题
            log.warn("数据库 schema 自检跳过（无法连接数据库，不影响启动判定）：{}", e.getMessage());
            return;
        }

        if (missing.isEmpty()) {
            log.info("数据库 schema 自检通过：{} 项必需列齐全", REQUIRED_COLUMNS.size());
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(70)).append("\n");
        sb.append("  数据库 schema 缺失（").append(missing.size()).append(" 项）：")
                .append("这些列被代码直接 SELECT，缺了会让对话与历史接口整体报错\n");
        sb.append("=".repeat(70)).append("\n");
        for (int i = 0; i < missing.size(); i++) {
            sb.append(i + 1).append(". ").append(missing.get(i)).append("\n");
        }
        sb.append("  执行：psql -h <SERVICE_IP> -U <DB_USERNAME> -d nexus_agent -f docs/sql/013_add_superseded_by.sql\n");
        sb.append("=".repeat(70));
        log.error(sb.toString());
//        🔴 只 WARN，不阻止启动（2026-10-06）。见类注释「失败语义」：
//        缺列是可降级的（运行期改查不带该列的 SQL），聊天与历史完全正常，
//        只是版本切换不生效。为了一个可降级功能让整个后端起不来，代价过大。
//        ⚠️ 这条**不受 nexus.agent.startup.fail-fast 控制** ——
//        那个开关管的是「必需配置缺失」，两者判据不同（能否降级 vs 有没有值）。
        log.warn("数据库 schema 自检未通过，服务照常启动（可降级：聊天与历史正常，"
                + "「重新生成的版本切换」不生效）。请尽快补执行 docs/sql/013_add_superseded_by.sql");
    }

    private boolean columnExists(Connection conn, String table, String column) throws Exception {
        String sql = """
                select 1
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = ?
                  and column_name = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
