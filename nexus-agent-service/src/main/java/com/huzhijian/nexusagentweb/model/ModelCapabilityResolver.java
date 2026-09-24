package com.huzhijian.nexusagentweb.model;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 模型服务商能力矩阵（P2-3）。
 * <p>
 * <b>解决什么</b>：原来请求里**无条件**塞 {@code enable_search} 与
 * {@code thinking} / {@code enable_thinking}（见 {@code ChatContextFactory.createModel}），
 * 而各家参数并不通用 —— 不认这些字段的服务商可能直接 400（开发日志 4.30 记录过
 * DeepSeek 调工具时的 400）。版本各异的中转/聚合服务更没法靠型号名猜。
 * <p>
 * <b>判定依据是 baseUrl 而不是模型名</b>：同一个型号在不同服务商下支持的参数不同
 * （例如 qwen 在百炼上认 {@code enable_search}，经某些中转站转发则不认），
 * 而"参数由谁转发"取决于 baseUrl。
 * <p>
 * <b>默认策略：未知即不下发。</b> 这是刻意的取舍 —— 少一个联网搜索/思考开关只是功能降级，
 * 而多发一个不支持的字段会让**整次对话直接失败**。命中不了内置表时会在日志里说明，
 * 并提示到 {@code nexus.agent.model.providers} 里声明，不会静默吞掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCapabilityResolver {

    /**
     * 服务商能力。
     *
     * @param thinking 是否支持「开/关思考」参数（{@code enable_thinking} / {@code thinking}）
     * @param search   是否支持联网搜索参数（{@code enable_search}）
     */
    public record Capability(boolean thinking, boolean search) {

        /** 什么都不支持：不下发任何额外参数（**默认**，最安全） */
        public static final Capability NONE = new Capability(false, false);
        public static final Capability THINKING_ONLY = new Capability(true, false);
        public static final Capability THINKING_AND_SEARCH = new Capability(true, true);
    }

    /**
     * 内置默认表：{@code baseUrl 片段 → 能力}。
     * <p>
     * **只放有把握的项**，不做猜测：猜错的代价是 400 或功能静默消失。
     * 没被内置表覆盖的服务商一律走 {@link Capability#NONE}，并在日志里提示如何声明。
     */
    private static final Map<String, Capability> BUILT_IN = Map.of(
            // 阿里云百炼（DashScope）OpenAI 兼容接口：enable_search / enable_thinking 是官方参数
            "dashscope.aliyuncs.com", Capability.THINKING_AND_SEARCH,
            // DeepSeek 官方：思考由具体型号决定（deepseek-reasoner），且没有 enable_search 参数
            "deepseek.com", Capability.NONE
    );

    private final AgentProperties agentProperties;

    /** 内置表 + 用户配置（用户同名 key 覆盖内置）合并后的最终表 */
    private Map<String, Capability> table = BUILT_IN;
    /** 是否已就「未命中任何服务商」提示过（避免每次请求刷日志） */
    private volatile boolean unknownProviderLogged = false;

    /** 构建最终能力表（由 Spring 在依赖注入后调用；测试直接调用它来跳过容器） */
    @PostConstruct
    public void init() {
        Map<String, AgentProperties.ProviderCapability> configured = agentProperties.getModel().getProviders();
        if (configured == null || configured.isEmpty()) {
            return;
        }
        Map<String, Capability> merged = new LinkedHashMap<>(BUILT_IN);
        configured.forEach((key, capability) -> {
            if (key == null || key.isBlank() || capability == null) {
                return;
            }
            merged.put(key.toLowerCase(Locale.ROOT),
                    new Capability(capability.isThinking(), capability.isSearch()));
        });
        this.table = Map.copyOf(merged);
        log.info("模型能力表已加载 {} 条（内置 {} 条 + 配置覆盖）", table.size(), BUILT_IN.size());
    }

    /**
     * 按 baseUrl 解析该服务商支持的能力。
     * <p>
     * 匹配规则：baseUrl **包含** 表中的 key（忽略大小写），多个命中取**最长**的 key
     * （这样 {@code dashscope.aliyuncs.com} 会优先于 {@code aliyuncs.com}
     * 这类更宽泛的片段）。
     */
    public Capability resolve(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Capability.NONE;
        }
        String url = baseUrl.toLowerCase(Locale.ROOT);
        return table.entrySet().stream()
                .filter(entry -> url.contains(entry.getKey()))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue)
                .orElseGet(() -> {
                    if (!unknownProviderLogged) {
                        log.info("服务商未在能力表内，本次不下发任何额外参数（thinking / search 均跳过）：baseUrl={}。"
                                        + "若该服务商确实支持，请在 nexus.agent.model.providers 中声明",
                                baseUrl);
                        unknownProviderLogged = true;
                    }
                    return Capability.NONE;
                });
    }
}
