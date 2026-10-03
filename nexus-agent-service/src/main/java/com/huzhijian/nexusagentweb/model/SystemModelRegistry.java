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
 * 系统内置模型（多供应商）注册表。
 * <p>
 * 配置在 {@code nexus.agent.system-models}，每家供应商一项。
 * 启动时把每一项建成一个 {@link StreamingChatModel}，按 id / modelName 索引。
 * <p>
 * <b>为什么要有它</b>：以前"系统默认模型"只有 yml 里
 * {@code langchain4j.open-ai.streaming-chat-model} 那一个 Bean ——
 * 想加第二个模型或换供应商都得改那一段。现在想加几家加几家，互不影响。
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

    /** id → 已建好的模型 */
    private final Map<String, StreamingChatModel> modelsById = new LinkedHashMap<>();
    /** id → 能力（视觉 / 窗口 / 输出） */
    private final Map<String, ModelCapabilities> capabilitiesById = new LinkedHashMap<>();
    /** id → 展示信息（给前端列表接口用） */
    @Getter
    private final List<AgentProperties.SystemModel> definitions = new ArrayList<>();

    @PostConstruct
    public void init() {
        List<AgentProperties.SystemModel> configured = agentProperties.getSystemModels();
        if (configured == null || configured.isEmpty()) {
            log.info("未配置 nexus.agent.system-models，沿用 langchain4j 的单一默认模型");
            return;
        }
        for (AgentProperties.SystemModel def : configured) {
            if (def.getId() == null || def.getId().isBlank()
                    || def.getBaseUrl() == null || def.getBaseUrl().isBlank()
                    || def.getApiKey() == null || def.getApiKey().isBlank()
                    || def.getModelName() == null || def.getModelName().isBlank()) {
                log.warn("系统模型配置不完整（id/baseUrl/apiKey/modelName 缺一），已跳过：{}", def.getId());
                continue;
            }
            if (modelsById.containsKey(def.getId())) {
                log.warn("系统模型 id 重复，后者覆盖前者：{}", def.getId());
            }
//            能力元数据复用同一套默认值（老数据/没填的字段都按默认来）
            Model meta = new Model();
            meta.setName(def.getModelName());
            meta.setVision(def.getVision());
            meta.setContextWindow(def.getContextWindow());
            meta.setMaxOutputTokens(def.getMaxOutputTokens());
            ModelCapabilities capabilities = ModelCapabilities.of(meta);

            modelsById.put(def.getId(), chatModelFactory.build(
                    def.getBaseUrl(), def.getApiKey(), def.getModelName(),
                    capabilities.maxOutputTokens(), false));
            capabilitiesById.put(def.getId(), capabilities);
            definitions.add(def);
            log.info("系统模型已就绪：id={} 名称={} 模型={} 供应商={} 视觉={} 窗口={} 输出={}",
                    def.getId(), def.getName(), def.getModelName(), def.getBaseUrl(),
                    capabilities.vision(), capabilities.contextWindow(), capabilities.maxOutputTokens());
        }
    }

    public boolean isEmpty() {
        return modelsById.isEmpty();
    }

    /**
     * 按请求里的模型信息匹配系统模型。
     * <p>
     * 先按 {@code model.id} 精确匹配，再按 {@code modelName} 匹配；
     * 都匹配不上时返回**第一个**（当作默认），因为系统模型本来就是给用户兜底用的。
     */
    public StreamingChatModel resolveModel(ModelDTO modelDTO) {
        if (isEmpty()) {
            return null;
        }
        if (modelDTO != null) {
            if (modelDTO.id() != null && modelsById.containsKey(modelDTO.id())) {
                return modelsById.get(modelDTO.id());
            }
            if (modelDTO.modelName() != null) {
                for (Map.Entry<String, StreamingChatModel> e : modelsById.entrySet()) {
                    AgentProperties.SystemModel def = definitionOf(e.getKey());
                    if (def != null && modelDTO.modelName().equals(def.getModelName())) {
                        return e.getValue();
                    }
                }
            }
        }
        return modelsById.values().iterator().next();
    }

    /** 取某个系统模型的能力（匹配不到时给默认值） */
    public ModelCapabilities resolveCapabilities(ModelDTO modelDTO) {
        if (isEmpty()) {
            return ModelCapabilities.DEFAULT;
        }
        if (modelDTO != null && modelDTO.id() != null && capabilitiesById.containsKey(modelDTO.id())) {
            return capabilitiesById.get(modelDTO.id());
        }
        if (modelDTO != null && modelDTO.modelName() != null) {
            for (Map.Entry<String, ModelCapabilities> e : capabilitiesById.entrySet()) {
                AgentProperties.SystemModel def = definitionOf(e.getKey());
                if (def != null && modelDTO.modelName().equals(def.getModelName())) {
                    return e.getValue();
                }
            }
        }
        return capabilitiesById.values().iterator().next();
    }

    private AgentProperties.SystemModel definitionOf(String id) {
        return definitions.stream().filter(d -> id.equals(d.getId())).findFirst().orElse(null);
    }
}
