package com.huzhijian.nexusagentweb.domain;

import com.huzhijian.nexusagentweb.em.ModelType;
import lombok.Data;

/**
 * 用户 API 配置里的一个模型条目。
 * <p>
 * 2026-10-03 新增三个**模型元数据**字段（vision / contextWindow / maxOutputTokens）：
 * 它们存在 {@code user_config.llm_api_token} 这个 **jsonb** 列里，
 * 所以**加字段不需要改表结构** —— 老数据反序列化时这些字段为 null，
 * 由 {@code ModelCapabilities.of()} 兜底成默认值。
 */
@Data
public class Model {
    private String name;
    private ModelType type;

    /**
     * 是否支持图片输入（视觉）。**默认 false** —— 不填就是不支持。
     * <p>
     * 填了 true 才会把图片发成真正的 {@code image_url}；
     * 不支持视觉的模型收到图片会被上游 API 直接拒绝，
     * 而"默认不支持"最多降级成 URL 文本，不会 400。
     */
    private Boolean vision;

    /**
     * 上下文窗口（token）。默认 65536（2026-10-06 从 256000 下调）。
     * 用于裁剪记忆窗口：本次能塞进提示词的历史 token 上限 =
     * min(全局上限, 上下文窗口 − 最大输出 token)。
     * <p>
     * ⚠️ 这个数决定「每轮发给模型的历史有多大」，也就决定了模型的 prefill 量 ——
     * 填大的代价是首字变慢，且会被 {@code max_tokens} 之外的真实上限打回。
     * 确实支持大窗口的模型才填真实值，理由见 {@code ModelCapabilities}。
     */
    private Integer contextWindow;

    /**
     * 单次最大输出 token。默认 16384（2026-10-06 从 32000 下调）。
     * <p>
     * ⚠️ 它会作为 {@code max_tokens} <b>原样发给服务商</b>，
     * 超过该模型的真实上限时请求会直接失败 —— 所以默认取向是宁小勿大。
     */
    private Integer maxOutputTokens;
}
