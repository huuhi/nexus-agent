package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.vo.QuotaVO;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: token 配额校验与用量累计（P2-8）。
 * <p>
 * 与 P2-6 的可观测性配套：那里负责"算出这次花了多少 token"，这里负责"记账 + 拦住超支"。
 * 用量数据本身就来自 {@code RunMetrics} 统计出的 {@code TokenUsage}。
 */
public interface QuotaService {

    /**
     * 校验用户配额，超限则抛 {@link com.huzhijian.nexusagentweb.exception.QuotaExceededException}。
     * <p>
     * 在**发起模型调用之前**调用：早失败可以省下一次完整的模型调用（也避免白建沙盒）。
     * <p>
     * 注意这是「事后记账 + 事前检查」的粗粒度方案：检查时并不知道本次会用多少，
     * 因此**允许最后一次小幅超额**（超支后下次对话才会被拒）。要精确控制需要在调用前
     * 估算 token，而估算本身不可靠（上下文长度随工具调用变化），故不做。
     * <p>
     * 若用户配了周期（{@code DAILY}/{@code MONTHLY}），这里还会**顺手做惰性重置** ——
     * 没有后台任务，也就不会有"定时任务挂了导致配额不刷新"这种故障模式。
     */
    void assertWithinQuota(Long userId);

    /**
     * 校验用户的「文件 + 产物」数量配额，超限则抛 {@link com.huzhijian.nexusagentweb.exception.QuotaExceededException}。
     * <p>
     * 2026-10-05 新增（{@code docs/sql/012}）：以前只有 token 配额，
     * 文件与产物想传多少传多少。现在按角色给默认额度
     * （普通 100 / 天、会员 1000 / 天、测试不限），额度按
     * {@code sys_file.create_time} 统计**当天**的条数，天然按天滚动。
     * <p>
     * 在**上传 / 产物落库之前**调用：此时文件还没传到 OSS，拦住能省一次无谓的上传。
     * <p>
     * ⚠️ 与 {@link #assertWithinQuota} 一样是「事前检查」：判定的是"现在已经用了多少"，
     * 所以一次批量上传多份文件时可能小幅超额（下一份才会被拒），这是刻意接受的成本。
     *
     * @param userId 用户 id；为 null 时直接放行（配额校验不该越权拦住未知身份的请求，
     *               那属于鉴权的职责）
     */
    void assertWithinFileQuota(Long userId);

    /**
     * 累加实际用量。**内部吞掉异常**：记账失败不应该让一次已经成功的对话变成失败。
     *
     * @param totalTokens 本次对话的总 token（in + out）；为 null 或 &lt;=0 时直接忽略
     */
    void recordUsage(Long userId, Integer totalTokens);

    /**
     * 查询当前用户的配额与用量（P2-8 遗留，供 {@code GET /api/user/quota} 展示）。
     * <p>
     * 与 {@link #assertWithinQuota} 不同，这里**不抛异常**：查询失败时返回
     * {@code QuotaVO.degraded()}（{@code degraded=true}），因为配额只是附加信息，
     * 不该让"没跑 003/007 迁移"的环境连设置页都打不开。
     * <p>
     * 为了让前端看到的是**当前周期**的真实用量（而不是上一周期遗留的累计值），
     * 这里同样会顺手做一次惰性重置 —— 该 UPDATE 幂等，重复调用没有副作用。
     *
     * @param userId 用户 ID；为 null 时返回 {@code degraded}
     */
    QuotaVO getQuota(Long userId);
}
