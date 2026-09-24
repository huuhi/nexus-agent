package com.huzhijian.nexusagentweb.service;

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
     */
    void assertWithinQuota(Long userId);

    /**
     * 累加实际用量。**内部吞掉异常**：记账失败不应该让一次已经成功的对话变成失败。
     *
     * @param totalTokens 本次对话的总 token（in + out）；为 null 或 &lt;=0 时直接忽略
     */
    void recordUsage(Long userId, Integer totalTokens);
}
