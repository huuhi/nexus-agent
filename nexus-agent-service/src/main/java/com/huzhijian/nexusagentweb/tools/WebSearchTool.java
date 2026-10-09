package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 工具名：公开给模型的名字与 {@code ToolSourceStore} 的键必须是同一个，抽出来防漂移 */
    private static final String TOOL_NAME = "web_search";

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
    /** 结构化结果的带外出口：把「来源」交给 SSE，而不用污染给模型看的文本 */
    private final ToolSourceStore sourceStore;
    /**
     * 解析后的 Key；null = 未配置（工具集不注册）。
     * <p>
     * 🔴 <b>不要给它加 {@code @Value}，也不要写成 {@code = ""}</b>（2026-10-09 事故，实测复现）：
     * <ul>
     *   <li>加 {@code @Value("${TAVILY_API_KEY}")} 且环境变量没配时，Spring 的
     *       {@code PropertySourcesPlaceholderConfigurer} 用 {@code resolveRequiredPlaceholders}
     *       解析，直接抛
     *       {@code PlaceholderResolutionException: Could not resolve placeholder 'TAVILY_API_KEY'}
     *       —— 被包装成 {@code Injection of autowired dependencies failed}，
     *       然后<b>整个应用起不来</b>。这与本工具「没配 Key 就不注册、其它功能照常」的设计直接冲突：
     *       一个可选功能的缺失，不该升级成全局不可用。</li>
     *   <li>就算配了 Key 也仍然坏：{@code private final String x = ""} 是<b>编译期常量</b>，
     *       javac 会把类内所有读取<b>常量折叠</b>成 {@code ""}。实测（JDK21 + Spring 6.2.17）：
     *       反射查字段确实被写成了 {@code "abc"}，但 getter 读到的还是 {@code ""}。
     *       于是 {@code apiKey != null} 恒为 true —— 没配 Key 时工具照样注册，然后每次调用 401。</li>
     * </ul>
     * 结论：Key 一律由构造器读 {@code System.getenv} 后赋值（见下面两个构造器），
     * 与 {@code WebExtractTool} 保持同一套写法。护栏见 {@code ValueInjectionGuardTest}。
     */
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 🔴 这个 {@code @Autowired} 不是装饰，少了它整个应用起不来（2026-10-08 容器启动失败事故）。
     * <p>
     * Spring 的构造器注入推断规则是：**只有当类"恰好有一个"构造器时**，才会无条件拿它去做注入。
     * 一旦出现两个及以上构造器、又没有任何一个标了 {@code @Autowired}，
     * Spring 就选不出来，退回「无参构造 + 字段注入」这条路 ——
     * 而本类没有无参构造器，于是上下文 refresh 直接失败：
     * <pre>
     * BeanInstantiationException: Failed to instantiate [WebSearchTool]: No default constructor found
     * </pre>
     * 并且沿着依赖链把整个应用拉挂
     * （chatController ← chatServiceImpl ← chatContextFactory ← toolRegistry ← webSearchTool）。
     * <p>
     * 为什么会踩到：下面那个 4 参构造器是为了让单测能注入显式 Key 才加的（包级可见），
     * 加的时候没意识到它打破了「唯一构造器」这个前提。
     * <b>今后本类再新增构造器，生产用的这个必须保持 {@code @Autowired}。</b>
     * <p>
     * ⚠️ 2026-10-09 补记：事故发生后有人「修」过一次，做法是把这个生产构造器<b>整段注释掉</b>、
     * 只留下测试用的那个 —— 那样确实让 {@code BeanConstructorInjectionGuardTest} 变绿了
     * （单构造器不算二义），却把 Key 的来源换成了 {@code @Value}（见 {@link #apiKey} 的说明），
     * 结果是「没配 Key 应用就起不来」，换个姿势炸得更大。
     * <b>正确修法只有一个：保留两个构造器，给生产这个标 {@code @Autowired}。</b>
     */
    @Autowired
    public WebSearchTool(AgentProperties properties, ToolCallGuard toolCallGuard, ToolSourceStore sourceStore) {
        this(properties, toolCallGuard, sourceStore, System.getenv("TAVILY_API_KEY"));
    }

    /** 供测试注入显式 Key（包级可见）；production 一律走上面的 {@code @Autowired} 构造器读环境变量 */
    WebSearchTool(AgentProperties properties, ToolCallGuard toolCallGuard, ToolSourceStore sourceStore,
                  String envKey) {
        this.properties = properties;
        this.toolCallGuard = toolCallGuard;
        this.sourceStore = sourceStore;
//        🔴 Key 只走环境变量（铁律 4）：写进 yml 会随仓库泄漏，且轮换要改代码
        this.apiKey = envKey == null || envKey.isBlank() ? null : envKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (apiKey == null) {
//        2026-10-08：用户配了 Key 却反馈「AI 说没有这个工具」。环境变量是**进程启动时**
//        一次性读入的，配完不重启进程永远读不到 —— 所以这里必须把「怎么确认、怎么修」
//        直接写在日志里，而不是只说一句"没启用"。
//        用 WARN 而不是 INFO：这是用户明确期望的功能缺失，应该在日志里能被一眼扫到。
            log.warn("联网搜索未启用：环境变量 TAVILY_API_KEY 未设置或为空 —— web_search 工具集**不注册**，"
                    + "模型会直接说「没有这个工具」（而不是调用了才报错）。"
                    + "排查：① 确认容器/进程真的带了这个环境变量；② **改完必须重启进程**（env 只在启动时读一次）；"
                    + "③ 重启后看启动日志里的「生效配置快照」，那一行会写明它读到没有。");
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
    @Tool(name = TOOL_NAME,
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

            recordSources(memoryId, response.body());
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

    /**
     * 抽取**结构化**来源列表 —— 前端「已搜索 N 个来源」的卡片数据。
     * <p>
     * 与 {@link #formatResults(String, String)} 是同一份数据的两种形态：
     * 那边是要喂给模型的文本，这边是要喂给 UI 的结构。
     * 走 {@code ToolSourceStore} 带外下发，而不是拼进返回值 ——
     * 返回值是要进模型上下文与历史消息的，多一份 JSON 就是白烧 token。
     * <p>
     * {@code index} 是 <b>1 开始</b>且与 {@link #formatResults} 的编号严格对齐：
     * 模型在回答里写 {@code [3]}，前端就能直接取 {@code sources[2]}。
     * <p>
     * 🔴 {@code index} 必须写成 {@code int}：全局 {@code JacksonConfig} 会把
     * {@code Long}/{@code long} 序列化成字符串（雪花 ID 精度），写成 {@code Long}
     * 会让前端收到 {@code "1"} 而不是 {@code 1} —— 与 2026-10-08 {@code ttfbMs}
     * 那个坑完全同源。
     */
    static List<Map<String, Object>> sources(String responseBody) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> list = new ArrayList<>();
            int index = 0;
            for (com.fasterxml.jackson.databind.JsonNode item : results) {
                String title = textOf(item, "title");
                String url = textOf(item, "url");
                if ((title == null || title.isBlank()) && (url == null || url.isBlank())) {
                    continue; // 与 formatResults 同一套过滤规则，两边编号才对得上
                }
                index++;
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("index", index);
                source.put("title", title == null ? "" : title);
                source.put("url", url == null ? "" : url);
//                摘要只给一小段：这是 SSE 帧载荷，不是模型上下文，没必要把全文搬过去
                source.put("snippet", clip(textOf(item, "content"), MAX_SNIPPET_CHARS));
                list.add(source);
            }
            return list;
        } catch (Exception e) {
            log.warn("web_search 来源抽取失败（不影响给模型的文本）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 摘要上限：够 hover 显示一句，又不至于让 SSE 帧膨胀 */
    private static final int MAX_SNIPPET_CHARS = 240;

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 把结构化来源交给 SSE（带外），失败与否都不影响本工具的返回值 */
    private void recordSources(Object memoryId, String responseBody) {
        if (sourceStore == null) {
            return;
        }
        sourceStore.record(memoryId, TOOL_NAME, sources(responseBody));
    }

    private static String textOf(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
