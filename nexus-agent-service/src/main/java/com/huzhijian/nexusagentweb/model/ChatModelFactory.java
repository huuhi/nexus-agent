package com.huzhijian.nexusagentweb.model;

import cn.hutool.crypto.digest.DigestUtil;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的「OpenAI 兼容流式模型」构造器。
 * <p>
 * 2026-10-03 抽出：以前只有 {@code ChatContextFactory} 一处会建模型；
 * 现在系统内置模型（多供应商）也要建，两边共用这一份，
 * 避免"用户自带模型支持思考、系统模型不支持"这种不一致。
 * <p>
 * <b>2026-10-05：模型实例按「配置指纹」缓存复用（首字延迟治理）</b>。
 * <p>
 * 原因：原来每次对话都 {@code new OpenAiStreamingChatModel(...)}，而
 * {@code new SpringRestClientBuilderFactory().create()} 内部走
 * Spring Boot 的 {@code ClientHttpRequestFactoryBuilder.detect().build(...)} ——
 * <b>每次都新建一整套 HTTP 客户端与连接池</b>（本项目 classpath 里有 webflux，
 * 检测结果是 Reactor Netty），直接后果有两个：
 * <ol>
 *   <li><b>连接永远无法复用</b>：每一轮对话都要重新 DNS + TCP + TLS 握手，
 *       这部分耗时实打实地压在「首字延迟」上（供应商侧再快也省不掉）；</li>
 *   <li><b>连接池泄漏</b>：上一轮的连接池没有任何人关闭，连接要等到 GC 才回收，
 *       跑久了会堆积大量半开连接与本地端口，表现为「偶尔某一次请求卡十几秒」。</li>
 * </ol>
 * 缓存后同一个「地址 + Key + 模型 + 参数」组合全程复用同一个客户端，
 * 连接与 TLS 会话得以复用。
 * <p>
 * ⚠️ <b>缓存 key 里不出现明文密钥</b>：{@code apiKey} 只参与 SHA-256 摘要，
 * 避免 heap dump / 调试器里能直接捞到用户凭据。
 * 用户改了 Key 或改了配置 → 指纹变化 → 自动建新的，旧条目由 FIFO 淘汰，
 * 所以「改了配置不生效」不会发生。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /**
     * 缓存上限（FIFO 淘汰）。
     * <p>
     * 每个条目都持有一个自己的连接池，因此这个数字直接决定「最多同时存在多少个连接池」。
     * 32 对「几个供应商 × 几个用户自带 Key × 思考开关」的组合足够宽裕，
     * 又不至于让连接数失控。
     */
    private static final int MAX_CACHED_MODELS = 32;

    private final ModelCapabilityResolver modelCapabilityResolver;

    /** key = 配置指纹（SHA-256），value = 该组合的模型实例。FIFO 由 {@link #build} 维护。 */
    private final Map<String, StreamingChatModel> cache = new LinkedHashMap<>();

    /**
     * @param baseUrl       OpenAI 兼容地址
     * @param apiKey        明文 Key（调用方负责解密）
     * @param modelName     实际模型名
     * @param maxOutputTokens 单次最大输出 token
     * @param isThinking    用户是否勾了思考
     */
    public StreamingChatModel build(String baseUrl, String apiKey, String modelName,
                                    int maxOutputTokens, boolean isThinking) {
        String fingerprint = fingerprint(baseUrl, apiKey, modelName, maxOutputTokens, isThinking);
        synchronized (cache) {
            StreamingChatModel cached = cache.get(fingerprint);
            if (cached != null) {
                return cached;
            }
            StreamingChatModel created = create(baseUrl, apiKey, modelName, maxOutputTokens, isThinking);
            cache.put(fingerprint, created);
            if (cache.size() > MAX_CACHED_MODELS) {
//                淘汰最早插入的那一条（LinkedHashMap 的迭代顺序即插入顺序）
                Iterator<String> oldest = cache.keySet().iterator();
                oldest.next();
                oldest.remove();
                log.debug("模型缓存已达上限 {}，淘汰最旧的一条", MAX_CACHED_MODELS);
            }
            return created;
        }
    }

    /**
     * 真正建一个模型实例（调用方需持有 {@link #cache} 的锁）。
     */
    private StreamingChatModel create(String baseUrl, String apiKey, String modelName,
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
     * 配置指纹：决定两个请求能不能共用同一个模型实例（也就是同一个连接池）。
     * <p>
     * 分隔符用 {@code \u0000}：它是 Key / 地址里都不可能出现的字符，
     * 避免 "a"+"bc" 与 "ab"+"c" 拼出同一个指纹。
     */
    private static String fingerprint(String baseUrl, String apiKey, String modelName,
                                      int maxOutputTokens, boolean isThinking) {
        String raw = baseUrl + '\u0000' + apiKey + '\u0000' + modelName
                + '\u0000' + maxOutputTokens + '\u0000' + isThinking;
        return DigestUtil.sha256Hex(raw);
    }

    /** 当前缓存了多少个模型实例（排查/监控用） */
    public int cachedModelCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** 清空缓存（用户改了 Key 想立刻生效时用；正常情况靠指纹变化自动区分） */
    public void evictAll() {
        synchronized (cache) {
            cache.clear();
        }
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
