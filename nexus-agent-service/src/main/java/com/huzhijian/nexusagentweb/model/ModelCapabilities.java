package com.huzhijian.nexusagentweb.model;

import com.huzhijian.nexusagentweb.domain.Model;

/**
 * 一次对话里**实际生效**的模型能力快照。
 * <p>
 * 来源优先级：用户在 API 配置里给该模型填的元数据 → 都没有就用默认值。
 * 老配置（没有这三个字段）反序列化后是 null，统一兜底，不会 NPE。
 *
 * @param vision           是否支持图片输入
 * @param contextWindow    上下文窗口（token）
 * @param maxOutputTokens  单次最大输出 token
 */
public record ModelCapabilities(boolean vision, int contextWindow, int maxOutputTokens) {

    public static final int DEFAULT_CONTEXT_WINDOW = 256_000;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_000;

    /** 系统默认模型 / 用户没配时用的能力：默认只支持文本 */
    public static final ModelCapabilities DEFAULT =
            new ModelCapabilities(false, DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_OUTPUT_TOKENS);

    /** 记忆窗口至少要留这么多 token，避免算出来是 0 或负数导致整段历史被裁光 */
    private static final int MIN_MEMORY_WINDOW = 2_048;

    /**
     * 从模型配置条目解析能力，字段为 null 就取默认值。
     */
    public static ModelCapabilities of(Model model) {
        if (model == null) {
            return DEFAULT;
        }
        Boolean vision = model.getVision();
        Integer contextWindow = model.getContextWindow();
        Integer maxOutputTokens = model.getMaxOutputTokens();
        return new ModelCapabilities(
                vision != null && vision,
                contextWindow == null || contextWindow <= 0 ? DEFAULT_CONTEXT_WINDOW : contextWindow,
                maxOutputTokens == null || maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens);
    }

    /**
     * 本次对话能塞进提示词的**历史 token 上限**。
     * <p>
     * 必须从上下文窗口里先扣掉输出位，否则"输入塞满 + 输出超限"会被上游 API 拒绝；
     * 同时受全局上限 {@code nexus.agent.memory.max-tokens} 约束（防止配置填得过大）。
     */
    public int memoryWindow(int globalMaxTokens) {
        int budget = contextWindow - maxOutputTokens;
        if (budget < MIN_MEMORY_WINDOW) {
//            上下文窗口填得比输出还小（配置填错）：保底给一个最小窗口，别把历史裁光
            budget = MIN_MEMORY_WINDOW;
        }
        return Math.min(globalMaxTokens, budget);
    }
}
