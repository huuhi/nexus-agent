package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.observability.RunMetrics;
import com.huzhijian.nexusagentweb.observability.RunMetricsReporter;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测性组件（P2-6）的纯单元测试：{@link RunMetrics} 的汇总输出 + {@link RunMetricsReporter} 的费用估算。
 * <p>
 * 不依赖 Spring、不访问网络。
 */
class RunMetricsTest {

    private RunMetrics metrics() {
        return new RunMetrics("run1234567890ab", "sess-1", 42L);
    }

    @Test
    @DisplayName("汇总行包含 runId / 会话 / 模型 / 耗时 / 工具序列，便于 grep")
    void summarizeContainsKeyFields() {
        RunMetrics m = metrics();
        m.recordToolExecuted("create_box", false);
        m.recordToolExecuted("execute_cmd", true);

        String line = m.summarize("¥0.0034");

        assertTrue(line.startsWith("RUN runId=run1234567890ab"), line);
        assertTrue(line.contains("session=sess-1"), line);
        assertTrue(line.contains("user=42"), line);
        assertTrue(line.contains("tools=2"), line);
        assertTrue(line.contains("(failed=1)"), line);
        assertTrue(line.contains("seq=[create_box,execute_cmd(failed)]"), line);
        assertTrue(line.contains("fee=¥0.0034"), line);
        assertTrue(line.endsWith("result=OK"), line);
    }

    @Test
    @DisplayName("没有 token 用量时显示 unavailable，而不是编造 0")
    void tokensUnavailableIsExplicit() {
        String line = metrics().summarize(null);

        assertTrue(line.contains("tokens=unavailable"), line);
        assertTrue(line.contains("model=unknown"), line);
        assertTrue(line.contains("fee=unpriced"), line);
    }

    @Test
    @DisplayName("模型名与 token 用量来自 ChatResponse，按 in/out/total 三段输出")
    void recordsModelAndTokensFromResponse() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("ok"))
                .metadata(ChatResponseMetadata.builder()
                        .modelName("deepseek-v4-flash")
                        .tokenUsage(new TokenUsage(100, 200, 300))
                        .build())
                .build();
        RunMetrics m = metrics();
        m.recordResponse(response);

        String line = m.summarize(null);

        assertTrue(line.contains("model=deepseek-v4-flash"), line);
        assertTrue(line.contains("tokens=100/200/300(in/out/total)"), line);
    }

    @Test
    @DisplayName("失败 Run 标记为 ERROR 并带异常类型（不记 message，避免噪音）")
    void errorRunIsMarked() {
        RunMetrics m = metrics();
        m.recordError(new IllegalStateException("boom"));

        String line = m.summarize(null);

        assertTrue(line.contains("result=ERROR"), line);
        assertTrue(line.contains("err=IllegalStateException"), line);
        assertFalse(line.contains("boom"), "汇总行只记异常类型，不记 message：" + line);
    }

    @Test
    @DisplayName("工具序列超上限时截断并标注省略数量，避免日志被撑爆")
    void toolSequenceIsBounded() {
        RunMetrics m = metrics();
        for (int i = 0; i < 35; i++) {
            m.recordToolExecuted("tool" + i, false);
        }

        String line = m.summarize(null);

        assertTrue(line.contains("...(+5)"), "应标注被截断的 5 条：" + line);
        assertEquals(35, m.toolCalls(), "计数仍然是完整的 35");
    }

    @Test
    @DisplayName("耗时字段有值且可读")
    void elapsedIsFormatted() {
        String line = metrics().summarize(null);

        assertTrue(line.contains("cost=") && line.contains("s"), line);
    }

    // ------------------------------------------------------------------
    //  费用估算
    // ------------------------------------------------------------------

    private RunMetricsReporter reporter(Map<String, double[]> prices) {
        AgentProperties props = new AgentProperties();
        prices.forEach((name, inOut) -> {
            AgentProperties.Price p = new AgentProperties.Price();
            p.setInput(inOut[0]);
            p.setOutput(inOut[1]);
            props.getObservability().getModelPrices().put(name, p);
        });
        return new RunMetricsReporter(props);
    }

    @Test
    @DisplayName("费用按每百万 token 单价计算")
    void estimateFeeComputes() {
        // 输入 2 元/百万，输出 8 元/百万；1000 输入 + 500 输出
        RunMetricsReporter reporter = reporter(Map.of("deepseek", new double[]{2, 8}));

        Double fee = reporter.estimateFee("deepseek", new TokenUsage(1000, 500, 1500));

        assertNotNull(fee);
        // 1000/1e6*2 + 500/1e6*8 = 0.002 + 0.004 = 0.006
        assertEquals(0.006, fee, 1e-9);
    }

    @Test
    @DisplayName("配置厂商前缀即可覆盖该系列模型")
    void estimateFeeMatchesByPrefix() {
        RunMetricsReporter reporter = reporter(Map.of("deepseek", new double[]{1, 1}));

        Double fee = reporter.estimateFee("deepseek-v4-flash", new TokenUsage(1_000_000, 0, 1_000_000));

        assertNotNull(fee);
        assertEquals(1.0, fee, 1e-9);
    }

    @Test
    @DisplayName("同时命中多个前缀时取最长前缀（更精确的那条）")
    void estimateFeePrefersLongestPrefix() {
        RunMetricsReporter reporter = reporter(Map.of(
                "deepseek", new double[]{1, 1},
                "deepseek-reasoner", new double[]{10, 10}));

        Double fee = reporter.estimateFee("deepseek-reasoner", new TokenUsage(1_000_000, 0, 1_000_000));

        assertEquals(10.0, fee, 1e-9, "应命中更精确的 deepseek-reasoner 单价");
    }

    @Test
    @DisplayName("未配置单价 / 无用量数据时返回 null（不编造金额）")
    void estimateFeeReturnsNullWhenUnknown() {
        RunMetricsReporter reporter = reporter(Map.of("deepseek", new double[]{1, 1}));

        assertNull(reporter.estimateFee("unknown-model", new TokenUsage(100, 100, 200)));
        assertNull(reporter.estimateFee("deepseek", null));
        assertNull(reporter.estimateFee(null, new TokenUsage(100, 100, 200)));
        assertNull(reporter.estimateFee("deepseek", new TokenUsage()), "空用量（三项均 null）也应返回 null");
        assertNull(reporter(Map.of()).estimateFee("deepseek", new TokenUsage(1, 1, 2)), "没配价格表就没有费用");
    }

    @Test
    @DisplayName("极小金额不显示成 ¥0.0000（那会被误读为免费）")
    void formatFeeAvoidsZeroLooking() {
        RunMetricsReporter reporter = reporter(Map.of());

        assertEquals("<¥0.0001", reporter.formatFee(0.0000001));
        assertEquals("¥0.0001", reporter.formatFee(0.0001));
    }
}
