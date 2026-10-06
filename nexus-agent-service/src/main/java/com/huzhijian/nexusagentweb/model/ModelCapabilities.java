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

    /**
     * 上下文窗口默认值（模型元数据没填时用）。
     * <p>
     * 🔴 <b>2026-10-06 两次调整，当天推翻了自己一次。</b>
     * 先从 256000 下调到 65536（当时认为「窗口大 → prefill 重 → 首字慢」），
     * 后经三次线上日志证明<b>该因果不成立</b>：真瓶颈是 skillResolve 查库与
     * CHAT_MEMORY 同请求内查 3 次，而当时 97 条消息（约 3 万 token）根本没撑满 65536。
     * 现在取 <b>131072</b>：对「没填元数据」的用户足够宽松，又不至于大到
     * 任何误填都能直接放行 100 万。
     * 这两个默认值（窗口 / 输出）的取向是<b>宁小勿大</b>，因为两种错法的代价严重不对等：
     * <ul>
     *   <li>填<b>大</b>了：记忆窗口 = {@code min(max-tokens, 窗口-输出)} 被撑大，
     *       每轮要发巨量历史给模型做 prefill（首字几秒、越聊越慢），
     *       而且 {@code maxOutputTokens} 会作为 {@code max_tokens} 原样发给服务商 ——
     *       超过真实上限时直接 400。</li>
     *   <li>填<b>小</b>了：只是少带一点上下文，模型照样能答，用户几乎无感。</li>
     * </ul>
     * 所以"不知道真实能力"时给保守值；确实支持大窗口的模型请在配置里显式填。
     */
    public static final int DEFAULT_CONTEXT_WINDOW = 131_072;

    /** 单次最大输出 token 默认值（同上，宁小勿大）。 */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;

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
