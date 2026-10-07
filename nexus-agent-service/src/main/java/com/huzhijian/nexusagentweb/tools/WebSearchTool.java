package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具（2026-10-07 新增，用户点名要的「基础能力」）。
 * <p>
 * 之前模型只知道自己训练截止前的知识，问「今天」「最新」「现在」类问题只能瞎编或坦白不知道。
 * 本工具给它一个「查一下再答」的出口。
 *
 * <h2>为什么选 Tavily</h2>
 * LLM 生态事实标准（LangChain4j 官方有集成、各框架教程都用它）、免费档每月 1000 次、
 * 返回 title/url/content 摘要（对模型友好，不需要再抓网页）。
 *
 * <h2>启用条件（必须同时满足，缺一整个工具集不注册）</h2>
 * <ol>
 *   <li>{@code nexus.agent.websearch.enabled=true}（默认开）；</li>
 *   <li>环境变量 {@code TAVILY_API_KEY} 有值。</li>
 * </ol>
 * 🔴 没配 Key 时工具集<b>不注册</b> —— 模型看不到这个工具，而不是调用了才报错。
 * 「看得见却用不了」的工具对模型是最糟的：它会反复尝试、浪费 token、还让用户等。
 * <p>
 * 🔴 按铁律 4（密钥不进代码/配置库）：Key **只走环境变量**，刻意不提供 yml 配置项。
 *
 * <h2>实现说明</h2>
 * 用 JDK {@link HttpClient} 而不是项目的 {@code HttpUtils}：后者绑定的是**沙盒服务**
 * 的 baseUrl 与超时配置，搜索要打的是外网 api.tavily.com，两者超时口径也不同
 * （搜索必须快，见 {@code websearch.timeout}）。失败一律转结构化结果让模型自纠
 * （见 §6.5 工具失败契约），并接 {@link ToolCallGuard} 防同参重复刷屏。
 */
@Component
@Slf4j
public class WebSearchTool implements AgentToolSet {

    private static final String TAVILY_ENDPOINT = "https://api.tavily.com/search";

    @Override
    public String key() {
        return "websearch";
    }

    @Override
    public String description() {
        return "联网搜索：查询实时信息、新闻、价格等训练数据之外的内容";
    }

    private final AgentProperties properties;
    private final ToolCallGuard toolCallGuard;
    /** 解析后的 Key；null = 未配置（工具集不注册） */
    private final String apiKey;
    private final HttpClient httpClient;

    public WebSearchTool(AgentProperties properties, ToolCallGuard toolCallGuard) {
        this(properties, toolCallGuard, System.getenv("TAVILY_API_KEY"));
    }

    /** 供测试注入显式 Key（包级可见）；production 一律走 public 构造器读环境变量 */
    WebSearchTool(AgentProperties properties, ToolCallGuard toolCallGuard, String envKey) {
        this.properties = properties;
        this.toolCallGuard = toolCallGuard;
//        🔴 Key 只走环境变量（铁律 4）：写进 yml 会随仓库泄漏，且轮换要改代码
        this.apiKey = envKey == null || envKey.isBlank() ? null : envKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (apiKey == null) {
            log.info("联网搜索未启用：未配置环境变量 TAVILY_API_KEY（websearch 工具集不注册，模型看不到 web_search）");
        } else {
            log.info("联网搜索已启用（Tavily，单次最多 {} 条结果）", properties.getWebsearch().getMaxResults());
        }
    }

    @Override
    public boolean enabled(com.huzhijian.nexusagentweb.tools.registry.ToolSelection selection) {
        return properties.getWebsearch().isEnabled() && apiKey != null;
    }

    /**
     * 联网搜索。
     * <p>
     * 返回格式（给模型读的纯文本，一行一条）：
     * <pre>
     * 1. [标题](url)
     *    摘要…
     * </pre>
     * 没命中时明确说"没有找到"，绝不能返回空串 —— 模型无法区分"没结果"和"工具坏了"。
     */
    @Tool(name = "web_search",
            value = "联网搜索最新信息。当问题涉及实时数据（新闻、价格、版本、天气、体育赛事等）"
                    + "或你不确定/训练截止之后的事实时使用。query 用具体的关键词组合，不要传整句话。")
    public String webSearch(@ToolMemoryId Object memoryId,
                            @P("搜索关键词，具体、精炼，可含年份（如\"2026 高考人数\"）") String query) {
        String blocked = toolCallGuard.interceptText(memoryId, "web_search",
                ToolCallGuard.fingerprint(query));
        if (blocked != null) {
            return blocked;
        }
        if (query == null || query.isBlank()) {
            return "error:搜索关键词为空。请提供具体的搜索关键词。";
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query.strip());
            body.put("max_results", Math.max(1, Math.min(10, properties.getWebsearch().getMaxResults())));
            body.put("search_depth", "basic");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_ENDPOINT))
                    .timeout(properties.getWebsearch().getTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(com.fasterxml.jackson.databind.json.JsonMapper
                            .builder().build().writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("web_search 被拒（{}）—— TAVILY_API_KEY 无效或过期", response.statusCode());
                return "error:搜索服务认证失败（TAVILY_API_KEY 无效）。请检查环境变量配置。请勿重复调用。";
            }
            if (response.statusCode() == 429) {
                return "error:搜索服务限流（免费额度用尽或请求过快）。请稍后再试，或改用你已知的信息回答。";
            }
            if (response.statusCode() >= 400) {
                log.warn("web_search 失败：status={} body={}", response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
                return "error:搜索服务返回 " + response.statusCode() + "。请勿重复调用。";
            }

            return formatResults(response.body(), query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error:搜索被中断。";
        } catch (Exception e) {
            // 工具返回值直接进模型上下文：必须记日志（否则线上无从追查），也必须给模型可读的话
            log.error("web_search 执行失败。query={}", query, e);
            return "error:搜索失败（" + e.getClass().getSimpleName() + "）。请勿重复调用同一参数。";
        }
    }

    /**
     * 把 Tavily 响应（{@code {"results":[{title,url,content}...]}}）格式化成模型易读的纯文本。
     * 解析失败按"没结果"处理而不是抛异常 —— 搜索是锦上添花，不该把对话炸掉。
     */
    static String formatResults(String responseBody, String query) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return "没有搜索到与「" + query + "」相关的结果。可以尝试换一组更具体的关键词。";
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : results) {
                String title = textOf(item, "title");
                String url = textOf(item, "url");
                String content = textOf(item, "content");
                if ((title == null || title.isBlank()) && (url == null || url.isBlank())) {
                    continue;
                }
                shown++;
                sb.append(shown).append(". ")
                        .append(title == null ? "(无标题)" : title)
                        .append(url == null ? "" : "\n   " + url)
                        .append(content == null || content.isBlank() ? "" : "\n   " + content)
                        .append("\n");
            }
            if (shown == 0) {
                return "没有搜索到与「" + query + "」相关的有效结果。";
            }
            return sb.toString().strip();
        } catch (Exception e) {
            log.warn("web_search 响应解析失败：{}", e.getMessage());
            return "error:搜索结果解析失败。请勿重复调用。";
        }
    }

    private static String textOf(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
