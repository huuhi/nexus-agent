package com.huzhijian.nexusagentweb.observability;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 把一次 Run 的指标汇总成一行日志输出（R6「不可观测」的对症）。
 * <p>
 * 输出样例：
 * <pre>
 * RUN runId=9f2c... session=8b1e... user=1 model=deepseek-v4-flash cost=7.6s \
 *     tokens=1234/567/1801(in/out/total) fee=¥0.0071 tools=3 seq=[create_box,execute_cmd,delete_box] result=OK
 * </pre>
 * <p>
 * <b>费用只在配了单价时才显示</b>：各厂商价格经常变动，写死在代码里必然过时，
 * 所以单价由配置提供（{@code nexus.agent.observability.model-prices}）；
 * 没配就显示 {@code fee=unpriced} —— 明确表示"不知道"，而不是给出错误的数字。
 * <p>
 * <b>为什么先落日志而不是落库</b>：日志已经能满足"查得到、能 grep、能统计"，
 * 且不引入表结构变更；将来要接统计面板时，再实现一个落库的 Reporter 即可
 * （本类已把"算"与"写"分开：{@link #estimateFee} 是纯函数，可直接复用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunMetricsReporter {

    private final AgentProperties agentProperties;

    /** 输出汇总。失败（有 error）时用 WARN，便于在日志里一眼筛出问题 Run */
    public void report(RunMetrics metrics) {
        if (!agentProperties.getObservability().isEnabled()) {
            return;
        }
        String fee = formatFee(estimateFee(metrics.modelName(), metrics.tokenUsage()));
        String line = metrics.summarize(fee);
        if (metrics.error() == null) {
            log.info(line);
        } else {
            log.warn(line);
        }
    }

    /**
     * 估算一次调用费用（单位：元）。
     * <p>
     * 匹配规则：先精确匹配模型名，再按**最长前缀**匹配（配置 key 可以是厂商/系列前缀，
     * 如 {@code deepseek} 能覆盖 {@code deepseek-v4-flash}）。
     *
     * @return null 表示无法估算（没配单价 / 没有用量数据）
     */
    public Double estimateFee(String modelName, TokenUsage usage) {
        if (modelName == null || usage == null) {
            return null;
        }
        AgentProperties.Price price = matchPrice(modelName);
        if (price == null) {
            return null;
        }
        Integer input = usage.inputTokenCount();
        Integer output = usage.outputTokenCount();
        if (input == null && output == null) {
            return null;
        }
        double million = 1_000_000d;
        double cost = (input == null ? 0 : input) / million * price.getInput()
                + (output == null ? 0 : output) / million * price.getOutput();
        return cost;
    }

    private AgentProperties.Price matchPrice(String modelName) {
        Map<String, AgentProperties.Price> prices = agentProperties.getObservability().getModelPrices();
        if (prices == null || prices.isEmpty()) {
            return null;
        }
        AgentProperties.Price exact = prices.get(modelName);
        if (exact != null) {
            return exact;
        }
        // 最长前缀优先：同时配了 "deepseek" 与 "deepseek-reasoner" 时，后者优先
        return prices.entrySet().stream()
                .filter(e -> modelName.startsWith(e.getKey()))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /** 费用文本：保留 4 位小数；极小值不显示成 ¥0.0000（那会被误读为免费）。公开以便单测。 */
    public String formatFee(Double yuan) {
        if (yuan == null) {
            return null;
        }
        if (yuan > 0 && yuan < 0.00005) {
            return "<¥0.0001";
        }
        return String.format("¥%.4f", yuan);
    }
}
