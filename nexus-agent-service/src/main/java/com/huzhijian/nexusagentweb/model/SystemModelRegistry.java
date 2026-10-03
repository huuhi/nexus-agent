package com.huzhijian.nexusagentweb.model;

import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.dto.ModelDTO;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统内置模型（多供应商、每家可挂多个模型）注册表。
 * <p>
 * 配置在 {@code nexus.agent.system-models}：**一家供应商一项，该项下 models 数组挂多个模型**
 * （共用同一份 baseUrl / apiKey）。启动时把每个模型建成一个 {@link StreamingChatModel}，按 id 索引。
 * <p>
 * <b>为什么要有它</b>：以前"系统默认模型"只有 yml 里
 * {@code langchain4j.open-ai.streaming-chat-model} 那一个 Bean ——
 * 想加第二个模型或换供应商都得改那一段。现在想加几家加几家、每家加几个模型都行。
 * <p>
 * ⚠️ **留空时行为完全不变**：{@link #isEmpty()} 为 true，调用方继续用
 * langchain4j starter 建的那个单一默认模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemModelRegistry {

    private final AgentProperties agentProperties;
    private final ChatModelFactory chatModelFactory;

    /** 模型 id → 已建好的模型 */
    private final Map<String, StreamingChatModel> modelsById = new LinkedHashMap<>();
    /** 模型 id → 条目信息（能力 + 展示名 + 所属供应商） */
    private final Map<String, SystemModelEntry> entriesById = new LinkedHashMap<>();
    /** 全部模型条目，按配置顺序（给前端列表接口用） */
    @Getter
    private final List<SystemModelEntry> entries = new ArrayList<>();

    /**
     * 一个已就绪的系统模型。
     *
     * @param id            模型唯一标识（前端 model.id 传它）
     * @param name          展示名
     * @param modelName     实际发给服务商的模型名
     * @param providerId    所属供应商 id（可空，前端分组用）
     * @param providerName  所属供应商展示名（可空）
     * @param capabilities  视觉 / 上下文窗口 / 最大输出
     */
    public record SystemModelEntry(String id, String name, String modelName,
                                   String providerId, String providerName,
                                   ModelCapabilities capabilities) {
    }

    @PostConstruct
    public void init() {
        List<AgentProperties.SystemModel> providers = agentProperties.getSystemModels();
        if (providers == null || providers.isEmpty()) {
            log.info("未配置 nexus.agent.system-models，沿用 langchain4j 的单一默认模型");
            return;
        }
        for (AgentProperties.SystemModel provider : providers) {
            if (provider == null) {
                continue;
            }
            if (isBlank(provider.getBaseUrl()) || isBlank(provider.getApiKey())) {
                log.warn("系统模型供应商配置不完整（baseUrl / apiKey 缺一），已跳过：id={}", provider.getId());
                continue;
            }
            List<AgentProperties.SystemModel.ModelEntry> declared =
                    provider.getModels() == null ? List.of() : provider.getModels();

            if (declared.isEmpty()) {
//                旧写法：该项自身就是唯一那个模型（modelName 写在供应商层）
                if (isBlank(provider.getModelName())) {
                    log.warn("系统模型供应商既没有 models 也没有 modelName，已跳过：id={}", provider.getId());
                    continue;
                }
                register(provider, null);
                continue;
            }
            for (AgentProperties.SystemModel.ModelEntry entry : declared) {
                if (entry == null || isBlank(entry.getModelName())) {
                    log.warn("系统模型条目缺 modelName，已跳过：供应商={}", provider.getId());
                    continue;
                }
                register(provider, entry);
            }
        }
        log.info("系统模型装载完成，共 {} 个", entries.size());
    }

    private void register(AgentProperties.SystemModel provider,
                          AgentProperties.SystemModel.ModelEntry entry) {
        String modelName = entry == null ? provider.getModelName() : entry.getModelName();
        String id = firstNonBlank(
                entry == null ? null : entry.getId(),
                provider.getId(),
                modelName);
        String name = firstNonBlank(
                entry == null ? null : entry.getName(),
                provider.getName(),
                modelName);

//        能力：模型级 → 供应商级 → 默认值（都由 ModelCapabilities.of 兜底）
        Model meta = new Model();
        meta.setName(modelName);
        meta.setVision(firstNonNull(entry == null ? null : entry.getVision(), provider.getVision()));
        meta.setContextWindow(firstNonNull(
                entry == null ? null : entry.getContextWindow(), provider.getContextWindow()));
        meta.setMaxOutputTokens(firstNonNull(
                entry == null ? null : entry.getMaxOutputTokens(), provider.getMaxOutputTokens()));
        ModelCapabilities capabilities = ModelCapabilities.of(meta);

        if (modelsById.containsKey(id)) {
            log.warn("系统模型 id 重复，后者覆盖前者：{}", id);
        }
        modelsById.put(id, chatModelFactory.build(
                provider.getBaseUrl(), provider.getApiKey(), modelName,
                capabilities.maxOutputTokens(), false));

        SystemModelEntry built = new SystemModelEntry(
                id, name, modelName, provider.getId(), provider.getName(), capabilities);
        entriesById.put(id, built);
//        覆盖时同步替换列表里的旧条目，避免列表与 map 不一致
        entries.removeIf(e -> e.id().equals(id));
        entries.add(built);

        log.info("系统模型已就绪：id={} 名称={} 模型={} 供应商={} 视觉={} 窗口={} 输出={}",
                id, name, modelName, provider.getBaseUrl(),
                capabilities.vision(), capabilities.contextWindow(), capabilities.maxOutputTokens());
    }

    public boolean isEmpty() {
        return modelsById.isEmpty();
    }

    /**
     * 按请求里的模型信息匹配系统模型。
     * <p>
     * 先按 {@code model.id} 精确匹配，再按 {@code modelName} 匹配；
     * 都匹配不上时返回**第一个**（系统模型本来就是兜底用的）。
     */
    public StreamingChatModel resolveModel(ModelDTO modelDTO) {
        if (isEmpty()) {
            return null;
        }
        SystemModelEntry entry = match(modelDTO);
        return entry == null
                ? modelsById.values().iterator().next()
                : modelsById.get(entry.id());
    }

    /** 取某个系统模型的能力（匹配不到时给第一个的能力） */
    public ModelCapabilities resolveCapabilities(ModelDTO modelDTO) {
        if (isEmpty()) {
            return ModelCapabilities.DEFAULT;
        }
        SystemModelEntry entry = match(modelDTO);
        return entry == null
                ? entries.get(0).capabilities()
                : entry.capabilities();
    }

    private SystemModelEntry match(ModelDTO modelDTO) {
        if (modelDTO == null) {
            return null;
        }
        if (modelDTO.id() != null && entriesById.containsKey(modelDTO.id())) {
            return entriesById.get(modelDTO.id());
        }
        if (modelDTO.modelName() != null) {
            for (SystemModelEntry e : entries) {
                if (modelDTO.modelName().equals(e.modelName())) {
                    return e;
                }
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}
