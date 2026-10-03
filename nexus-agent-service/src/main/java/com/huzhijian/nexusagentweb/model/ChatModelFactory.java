package com.huzhijian.nexusagentweb.model;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一的「OpenAI 兼容流式模型」构造器。
 * <p>
 * 2026-10-03 抽出：以前只有 {@code ChatContextFactory} 一处会建模型；
 * 现在系统内置模型（多供应商）也要建，两边共用这一份，
 * 避免"用户自带模型支持思考、系统模型不支持"这种不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    private final ModelCapabilityResolver modelCapabilityResolver;

    /**
     * @param baseUrl       OpenAI 兼容地址
     * @param apiKey        明文 Key（调用方负责解密）
     * @param modelName     实际模型名
     * @param maxOutputTokens 单次最大输出 token
     * @param isThinking    用户是否勾了思考
     */
    public StreamingChatModel build(String baseUrl, String apiKey, String modelName,
                                    int maxOutputTokens, boolean isThinking) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxOutputTokens)
                .returnThinking(true)
                .sendThinking(true)
                .customParameters(extraBody(baseUrl, modelName, isThinking))
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build();
    }

    /**
     * 按服务商能力组装「额外参数」（P2-3）。
     * <p>
     * 只下发该服务商支持的字段：不认某个字段的服务商可能直接 400，而少一个开关只是功能降级，
     * 两者代价不对等。判定依据是 baseUrl（见 {@link ModelCapabilityResolver}）——
     * 同一型号经不同服务商转发时支持的参数并不相同。
     * <p>
     * 用户勾了思考但服务商不支持时会打日志说明，不静默丢弃。
     */
    public Map<String, Object> extraBody(String baseUrl, String modelName, boolean isThinking) {
        ModelCapabilityResolver.Capability capability = modelCapabilityResolver.resolve(baseUrl);
        Map<String, Object> extraBody = new HashMap<>();
        if (capability.thinking()) {
            if (isThinking) {
                log.debug("开启思考：model={}", modelName);
                extraBody.put("thinking", Map.of("type", "enabled"));
                extraBody.put("enable_thinking", true);
            } else {
                log.debug("关闭思考：model={}", modelName);
                extraBody.put("thinking", Map.of("type", "disabled"));
                extraBody.put("enable_thinking", false);
            }
        } else if (isThinking) {
            log.info("服务商不支持思考参数，本次已忽略 thinking 开关：baseUrl={} model={}", baseUrl, modelName);
        }
        if (capability.search()) {
            extraBody.put("enable_search", true);
        }
        return extraBody;
    }
}
