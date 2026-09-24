package com.huzhijian.nexusagentweb.service.impl;

import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * 周期配额（每日/每月重置）需要额外记录周期起点，且要考虑跨周期边界的记账，
 * 当前不做 —— 见 {@code docs/sql/003_add_user_token_quota.sql} 的说明。
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
        Decision decision = evaluate(user.getTokenQuota(), user.getTokenUsed());
        if (!decision.allowed()) {
            log.warn("token 配额超限，本次对话被拒绝：userId={} 已用={} 配额={}",
                    userId, decision.used(), decision.quota());
            throw new QuotaExceededException(
                    "您的 token 配额已用完（已用 %d / 上限 %d）。请联系管理员调整配额后再试。"
                            .formatted(decision.used(), decision.quota()));
        }
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
