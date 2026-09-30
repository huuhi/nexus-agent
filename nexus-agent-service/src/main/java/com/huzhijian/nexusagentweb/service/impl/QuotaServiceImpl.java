package com.huzhijian.nexusagentweb.service.impl;

import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.em.QuotaPeriod;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: token 配额的默认实现（P2-8）。
 * <p>
 * 配额的判定规则刻意做得极简且**可解释**：
 * <pre>
 *   quota 为 null 或 &lt;= 0  → 不限制（放行）
 *   used  &gt;= quota          → 拒绝
 * </pre>
 *
 * <h3>周期重置（P2-8 遗留，见 {@code docs/sql/007}）</h3>
 * `token_used` 原本只增不减，配了额度的用户用完就永久被拒。现在支持
 * {@code DAILY} / {@code MONTHLY}：
 * <ul>
 *   <li>**惰性重置**，不跑定时任务 —— 在 {@link #assertWithinQuota} 里顺手判断
 *       （`token_period_start < 当前周期起点` 就清零）。没有后台任务，
 *       也就不会有"定时任务挂了导致所有人配额不刷新"的故障模式；
 *       代价是长期不说话的用户不会被清零（但他也没在消耗额度）。</li>
 *   <li>重置是一条带 WHERE 的原子 UPDATE（见 {@code UserMapper.xml#resetQuotaPeriod}），
 *       并发安全且幂等。</li>
 *   <li>默认 {@code NONE}：存量用户与未执行 {@code 007} 的库，行为与 003 **完全一致**。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaServiceImpl implements QuotaService {

    private final UserMapper userMapper;
    private final AgentProperties agentProperties;

    @Override
    public void assertWithinQuota(Long userId) {
        if (userId == null || !agentProperties.getQuota().isEnabled()) {
            return;
        }
        User user;
        try {
            user = userMapper.selectById(userId);
        } catch (Exception e) {
            // 兜底：最典型的是库里还没执行 docs/sql/003（缺 token_quota / token_used 列），
            // 此时实体查询会报 SQL 错误。**配额校验不是主流程的一部分**，
            // 绝不能让"忘了建列"把整次对话打挂 —— 降级为放行 + 明确提示。
            log.warn("配额校验已跳过（查询用户失败，本次放行）：userId={} 原因={}。"
                    + "若日志提示缺列，请执行 docs/sql/003_add_user_token_quota.sql", userId, e.getMessage());
            return;
        }
        if (user == null) {
            // 用户不存在属于鉴权/业务校验的问题，不在这里报"配额超限"（会误导排查方向）
            log.debug("配额校验跳过：用户不存在 userId={}", userId);
            return;
        }

        // 跨周期时清零（惰性重置，见类注释）。返回重置后的真实用量。
        long used = resetPeriodIfDue(userId, user);

        Decision decision = evaluate(user.getTokenQuota(), used);
        if (!decision.allowed()) {
            log.warn("token 配额超限，本次对话被拒绝：userId={} 已用={} 配额={} 周期={}",
                    userId, decision.used(), decision.quota(), periodOf(user));
            throw new QuotaExceededException(
                    "您的 token 配额已用完（已用 %d / 上限 %d）。请联系管理员调整配额，或等待下一周期重置（当前周期：%s）。"
                            .formatted(decision.used(), decision.quota(), describePeriod(periodOf(user))));
        }
    }

    /**
     * 若已跨周期则把用量清零，返回**重置后**的已用量。
     * <p>
     * 单独抽出来是因为「判定用多少」和「要不要重置」是两件事，
     * 混在一起就会出现"重置了但仍按旧用量判定"的 bug。
     */
    private long resetPeriodIfDue(Long userId, User user) {
        long used = user.getTokenUsed() == null ? 0L : user.getTokenUsed();
        QuotaPeriod period = periodOf(user);
        if (!period.resets()) {
            return used;
        }
        LocalDateTime periodStart = period.startOf(LocalDate.now());
        try {
            int rows = userMapper.resetQuotaPeriod(userId, periodStart);
            if (rows > 0) {
                log.info("token 配额已按 {} 周期重置：userId={} 周期起点={}（重置前用量 {}）",
                        period, userId, periodStart, used);
                return 0L;
            }
            return used;
        } catch (Exception e) {
            // 典型原因：docs/sql/007 没执行（缺 token_period 列）。
            // 降级为「不重置」而不是「放行」—— 仍然按累计用量判定，比直接放行更保守。
            log.warn("周期重置失败（已按累计用量判定，未清零）：userId={} 周期={} 原因={}。"
                    + "若提示缺列，请执行 docs/sql/007_add_user_token_quota_period.sql",
                    userId, period, e.getMessage());
            return used;
        }
    }

    /** 用户自己的周期优先；没配就用全局默认（{@code nexus.agent.quota.period}，默认 NONE） */
    private QuotaPeriod periodOf(User user) {
        String raw = user.getTokenPeriod();
        if (raw == null || raw.isBlank()) {
            return agentProperties.getQuota().getPeriod() == null
                    ? QuotaPeriod.NONE
                    : agentProperties.getQuota().getPeriod();
        }
        return QuotaPeriod.of(raw);
    }

    private static String describePeriod(QuotaPeriod period) {
        return switch (period) {
            case NONE -> "不重置（累计）";
            case DAILY -> "每日";
            case MONTHLY -> "每月";
        };
    }

    @Override
    public void recordUsage(Long userId, Integer totalTokens) {
        if (userId == null || totalTokens == null || totalTokens <= 0) {
            return;
        }
        try {
            int rows = userMapper.addTokenUsage(userId, totalTokens);
            if (rows == 0) {
                log.warn("累加 token 用量未命中任何用户：userId={} delta={}", userId, totalTokens);
            } else {
                log.debug("token 用量已累加：userId={} delta={}", userId, totalTokens);
            }
        } catch (Exception e) {
            // 记账失败不能让一次已经成功的对话变成失败 —— 这是"统计口径"问题，不是业务失败
            log.warn("累加 token 用量失败（已忽略，不影响本次对话）：userId={} delta={} 原因={}",
                    userId, totalTokens, e.getMessage());
        }
    }

    @Override
    public QuotaVO getQuota(Long userId) {
        if (userId == null) {
            return QuotaVO.degraded();
        }
        User user;
        try {
            user = userMapper.selectById(userId);
        } catch (Exception e) {
            // 典型原因：003 / 007 没执行。返回 degraded 而不是抛异常 ——
            // 配额是附加信息，不该让"没跑迁移"的环境连设置页都打不开。
            log.warn("查询配额失败：userId={} 原因={}。若提示缺列，请执行 docs/sql/003 与 007",
                    userId, e.getMessage());
            return QuotaVO.degraded();
        }
        if (user == null) {
            return QuotaVO.degraded();
        }

        QuotaPeriod period = periodOf(user);
        // 这里也走一次惰性重置：否则前端看到的会是"上一周期遗留的用量"，
        // 而周期起点却显示成当前周期 —— 数字自相矛盾，比不显示更糟。
        // 该 UPDATE 幂等（见 resetQuotaPeriod 的 WHERE），GET 重复调用没有副作用。
        long used = resetPeriodIfDue(userId, user);

        Long quota = user.getTokenQuota();
        boolean unlimited = quota == null || quota <= 0;

        return QuotaVO.builder()
                .quota(unlimited ? null : quota)
                .used(used)
                .remaining(unlimited ? null : Math.max(0, quota - used))
                .unlimited(unlimited)
                .period(period.name())
                .periodStart(period.resets() ? period.startOf(LocalDate.now()) : null)
                .degraded(false)
                .build();
    }

    /**
     * 纯函数形式的配额判定（便于单测，不依赖数据库与 Spring）。
     *
     * @param quota 配额上限；null 或 &lt;=0 表示不限制
     * @param used  已用量；null 视为 0
     */
    public static Decision evaluate(Long quota, Long used) {
        long usedSafe = used == null ? 0L : used;
        if (quota == null || quota <= 0) {
            return new Decision(true, quota, usedSafe);
        }
        return new Decision(usedSafe < quota, quota, usedSafe);
    }

    /**
     * 判定结果。
     *
     * @param allowed 是否放行
     * @param quota   配额上限（null 表示不限制）
     * @param used    已用量（已做 null 兜底）
     */
    public record Decision(boolean allowed, Long quota, Long used) {
    }
}
