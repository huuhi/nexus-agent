package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 网页正文提取工具（2026-10-08 新增，工具名 {@code web_extract}）。
 * <p>
 * 补的是 {@link WebSearchTool} 留下的那半个能力：搜索只给回
 * <b>一条摘要</b>（title/url/content），模型看到摘要后想读原文时没有路可走 ——
 * 只能凭摘要猜，或者再搜一轮。本工具把它接上：<b>给 URL，拿正文</b>。
 *
 * <h2>为什么它比看上去便宜</h2>
 * Tavily 的 {@code /extract} 计费是「每 <b>5 个成功提取的 URL</b> 扣 1 credit」，
 * 而且<b>失败不计费</b>。对照 <code>/search</code> 的「一次搜索 1 credit」，
 * 拉 5 个网页的正文和搜一次是一个价 —— 这是整个 Tavily 套餐里性价比最高的端点。
 * 所以默认 {@code extractMaxUrls = 5}：恰好吃掉一个计费单位，不浪费。
 *
 * <h2>🔴 为什么必须在进入之前限字数</h2>
 * 工具返回值直接进模型上下文。一个完整网页动辄几万字符，
 * 5 条全量塞进来就是十几万字符 —— 上下文 $^\rightarrow$ 窗口直接被顶穿，
 * 后面的历史会被丢弃（见 {@code contextRatio}）。
 * 这里加了两道闸门：单条 {@code extractMaxCharsPerUrl} + 总体 {@code extractMaxTotalChars}。
 * 截断要在<b>服务端</b>做而不是让模型自己精简 —— 后者意味着那些字已经付过 token 了。
 *
 * <h2>partial success 是常态，不是异常</h2>
 * 官方文档明确说：<b>HTTP 200 也可能 {@code results} 为空</b>，失败逐个落在
 * {@code failed_results} 里；而且<b>结果顺序不保证</b>。
 * 所以这里按 URL 逐个回报成败，成功多少给多少，绝不因为一条挂掉就整体失败
 * （那会让模型以为"这个工具坏了"从而放弃整条路）。
 *
 * <h2>安全</h2>
 * 每个 URL 在出网前都过 {@link UrlGuard} —— 由<b>模型提供</b>的 URL 等同于用户输入，
 * 不加 SSRF 校验就等于让任何登录用户能让服务端去连内网。
 * 被拒的 URL 不发出请求，按单条失败回报（含原因，模型能据此换策略）。
 *
 * @see <a href="https://docs.tavily.com/documentation/api-reference/endpoint/extract">Tavily Extract API</a>
 */
@Slf4j
@Component
public class WebExtractTool implements AgentToolSet {

    private static final String TAVILY_EXTRACT_ENDPOINT = "https://api.tavily.com/extract";

    /** Tavily 侧硬性上限（单次最多 20 个 URL），服务端再怎么配也不能越过 */
    private static final int HARD_MAX_URLS = 20;

    private static final ObjectMapperHolder MAPPER = new ObjectMapperHolder();

    @Override
    public String key() {
        return "webextract";
    }

    @Override
    public String description() {
        return "网页正文提取：把给定的一个或多个 URL 抓成可读正文";
    }

    private final AgentProperties properties;
    private final UrlGuard urlGuard;
    private final ToolCallGuard toolCallGuard;
    /** 解析后的 Key；null = 未配置（工具集不注册） */
    private final String apiKey;
    private final HttpClient httpClient;
    /** Tavily 地址。留 seam 是为了单测能起一个本地假服务，真跑一遍请求/响应而不必打外网 */
    private final String endpoint;

    /**
     * 🔴 这个 {@code @Autowired} 不是装饰 —— 本类有多个构造器（其余给单测注入 Key / 端点用），
     * 一旦没有它 Spring 会选不出构造器、退回无参实例化，而本类没有无参构造器，
     * 结果是<b>整个应用起不来</b>（2026-10-08 事故，见 {@code BeanConstructorInjectionGuardTest}）。
     * <p>
     * 🔴 Key 的取值走 {@link AgentProperties.Websearch#API_KEY_EXPRESSION}：
     * <b>配置项 {@code nexus.agent.websearch.api-key} 优先，环境变量 {@code TAVILY_API_KEY} 兜底</b>。
     * 2026-10-09 之前这里是 {@code System.getenv("TAVILY_API_KEY")} —— 那条路径绕开 Spring，
     * 写在外部 yml / 面板 {@code .env.properties} 里的值一律读不到（与 {@code JwtUtil} /
     * {@code EncryptorFactory} 在 2026-10-03 踩过的是同一个坑）。
     */
    @Autowired
    public WebExtractTool(AgentProperties properties, UrlGuard urlGuard, ToolCallGuard toolCallGuard,
                          @Value(AgentProperties.Websearch.API_KEY_EXPRESSION) String apiKey) {
        this(properties, urlGuard, toolCallGuard, apiKey, TAVILY_EXTRACT_ENDPOINT);
    }

    /** 供测试改写到本地假服务（包级可见）；同时也用于测试注入显式 Key */
    WebExtractTool(AgentProperties properties, UrlGuard urlGuard, ToolCallGuard toolCallGuard,
                   String envKey, String endpoint) {
        this.properties = properties;
        this.urlGuard = urlGuard;
        this.toolCallGuard = toolCallGuard;
//        🔴 空串归一成 null：Spring 解析不到时给的是空串（占位符末尾有默认值），
//        而下面 enabled() 判断的是 != null。不归一就会「空 Key 也算配了」→ 工具注册了但每次 401。
        this.apiKey = envKey == null || envKey.isBlank() ? null : envKey.trim();
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        if (this.apiKey == null) {
//        与 web_search 共用同一个 Key，排查口径也一致（见 WebSearchTool 的同款日志）：
//        2026-10-09 起有**两条**配置路径，报错必须把两条都写出来 ——
//        否则用户只会反复检查自己写的那一条。
            log.warn("正文提取未启用：配置项 {} 与环境变量 TAVILY_API_KEY 都没有值 —— web_extract 工具集**不注册**。"
                            + "它与 web_search 共用同一个 Key；两条路任选其一：① 外部配置文件里写 {}: tvly-xxx；"
                            + "② 进程环境变量里写 TAVILY_API_KEY=tvly-xxx。改完必须重启进程，"
                            + "并到启动日志的「生效配置快照」里核对那一行（会写明读到没有、以及来源是哪一条）。",
                    AgentProperties.Websearch.API_KEY_PROPERTY,
                    AgentProperties.Websearch.API_KEY_PROPERTY);
        } else {
            log.info("正文提取已启用（Tavily，单次最多 {} 个 URL，单条最多 {} 字符）",
                    properties.getWebsearch().getExtractMaxUrls(),
                    properties.getWebsearch().getExtractMaxCharsPerUrl());
        }
    }

    @Override
    public boolean enabled(ToolSelection selection) {
        return properties.getWebsearch().isEnabled() && apiKey != null;
    }

    /**
     * 抓取一个或多个 URL 的正文。
     * <p>
     * 返回格式（给模型读的纯文本，一条一段）：
     * <pre>
     * [1] https://example.com/a
     * 正文……
     *
     * [2] https://example.com/b  ✗ Failed to retrieve content
     * </pre>
     * 失败的条目也<b>照常列出并写明原因</b> —— 否则模型不知道那个 URL 到底是被跳过还是读了但没内容。
     */
    @Tool(name = "web_extract",
            value = "抓取指定网页的正文内容。用于已经知道具体 URL（例如上一步 web_search 的结果里给出的链接，"
                    + "或用户直接贴的链接）、需要读完整细节而不是摘要时。"
                    + "urls 传 URL 列表，一次最多 20 个；不要用它去猜 URL，也不要对同一个 URL 反复调用。")
    public String webExtract(@ToolMemoryId Object memoryId,
                             @P("要抓取正文的 URL 列表，一次最多 20 个") List<String> urls) {
//        指纹用「归一化后的整个 URL 列表」：同参重复调用直接拦截，省额度也免得刷屏
        String fingerprint = ToolCallGuard.fingerprint(normalizeKey(urls));
        String blocked = toolCallGuard.interceptText(memoryId, "web_extract", fingerprint);
        if (blocked != null) {
            return blocked;
        }

        if (urls == null || urls.isEmpty()) {
            return "error:没有给出任何 URL。请提供要抓取正文的网页地址。";
        }

//        ===== 第一关：SSRF 校验 =====
//        模型给的 URL = 用户输入：必须在出网前逐个校验。被拒的不发出请求，按单条失败回报。
        List<String> accepted = new ArrayList<>();
        List<Doc> docs = new ArrayList<>();
        for (String raw : urls) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (String one : raw.split("[\\s,，;；]+")) {
                String trimmed = one.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    urlGuard.validate(trimmed, "待抓取的 URL");
                    accepted.add(trimmed);
                } catch (IllegalArgumentException e) {
                    log.warn("web_extract 拒绝了一个 URL（{}）：{}", trimmed, e.getMessage());
                    docs.add(Doc.failed(trimmed, "不允许抓取：" + e.getMessage()));
                }
            }
        }
        if (accepted.isEmpty()) {
//            一条都没过校验：这不是"提取失败"，是参数本身不合法，直接告诉模型重来
            return "error:给出的 URL 全部不可用（协议必须是 http/https，且不能指向内网或本机）。"
                    + (docs.isEmpty() ? "" : " " + docs.get(0).error());
        }

//        ===== 第二关：数量封顶（对齐计费单位，也防止模型一口气丢 100 个链接） =====
        int maxUrls = Math.max(1, Math.min(HARD_MAX_URLS, properties.getWebsearch().getExtractMaxUrls()));
        List<String> dropped = new ArrayList<>();
        if (accepted.size() > maxUrls) {
            dropped = accepted.subList(maxUrls, accepted.size()).stream().toList();
            accepted = accepted.subList(0, maxUrls);
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("urls", accepted);
            body.put("extract_depth", properties.getWebsearch().isExtractAdvanced() ? "advanced" : "basic");
            body.put("format", "markdown");
            body.put("include_images", false);
            body.put("include_favicon", false);
            body.put("timeout", clampTimeout(properties.getWebsearch().getExtractTimeout()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(properties.getWebsearch().getExtractTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.write(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                log.warn("web_extract 被拒（{}）—— TAVILY_API_KEY 无效或过期", response.statusCode());
                return "error:正文提取服务认证失败（TAVILY_API_KEY 无效）。请检查环境变量配置。请勿重复调用。";
            }
            if (response.statusCode() == 429) {
                return "error:正文提取服务限流（额度用尽或请求过快）。请稍后再试，或改用你已知的信息回答。";
            }
            if (response.statusCode() >= 400) {
                log.warn("web_extract 失败：status={} body={}", response.statusCode(),
                        response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
                return "error:正文提取服务返回 " + response.statusCode() + "。请勿重复调用。";
            }

            docs.addAll(parse(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "error:正文提取被中断。";
        } catch (Exception e) {
            log.error("web_extract 执行失败。urls={}", accepted, e);
            return "error:正文提取失败（" + e.getClass().getSimpleName() + "）。请勿重复调用同一批 URL。";
        }

        if (!dropped.isEmpty()) {
            for (String d : dropped) {
                docs.add(Doc.failed(d, "超出单次调用上限（" + maxUrls + " 个），已跳过"));
            }
        }
        return format(docs, properties.getWebsearch().getExtractMaxCharsPerUrl(),
                properties.getWebsearch().getExtractMaxTotalChars());
    }

    /** Tavily 要求 timeout 落在 [1,60] 秒，超出会让请求被拒 */
    private static double clampTimeout(Duration timeout) {
        long sec = timeout == null ? 20 : timeout.toSeconds();
        if (sec < 1) {
            return 1;
        }
        return Math.min(60, sec);
    }

    /** 用于重复调用指纹：原样拼接会让顺序不同产生不同指纹，这里排序后再拼 */
    private static String normalizeKey(List<String> urls) {
        if (urls == null) {
            return "";
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String u : urls) {
            if (u != null && !u.isBlank()) {
                set.add(u.trim().toLowerCase());
            }
        }
        List<String> sorted = new ArrayList<>(set);
        java.util.Collections.sort(sorted);
        return String.join("|", sorted);
    }

    /**
     * 一条提取结果。成功的带正文，失败的带原因 —— <b>两者都要留在最终文本里</b>，
     * 否则模型无法区分"这条没抓到"和"这条压根没试"。
     */
    public record Doc(String url, String content, boolean ok, String error) {
        static Doc ok(String url, String content) {
            return new Doc(url, content, true, null);
        }

        static Doc failed(String url, String error) {
            return new Doc(url, null, false, error);
        }
    }

    /**
     * 解析 Tavily {@code /extract} 响应。
     * <p>
     * 官方明确：<b>HTTP 200 也可能 {@code results} 为空</b>，且<b>顺序不保证</b>。
     * 所以这里把 {@code results} 与 {@code failed_results} 一视同仁地收集起来，
     * 解析异常按"整体失败"返回一条可读错误，而不是抛异常炸掉对话。
     */
    static List<Doc> parse(String responseBody) {
        List<Doc> docs = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.read(responseBody);
            com.fasterxml.jackson.databind.JsonNode results = root.get("results");
            if (results != null && results.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : results) {
                    String url = textOf(item, "url");
                    String content = textOf(item, "raw_content");
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    docs.add(Doc.ok(url, content));
                }
            }
            com.fasterxml.jackson.databind.JsonNode failed = root.get("failed_results");
            if (failed != null && failed.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : failed) {
                    String url = textOf(item, "url");
                    if (url == null || url.isBlank()) {
                        continue;
                    }
                    docs.add(Doc.failed(url, textOf(item, "error") == null ? "提取失败" : textOf(item, "error")));
                }
            }
            return docs;
        } catch (Exception e) {
            log.warn("web_extract 响应解析失败：{}", e.getMessage());
            return List.of(Doc.failed("(响应)", "提取结果解析失败"));
        }
    }

    /**
     * 把解析结果渲染成给模型读的文本，同时施加字数闸门。
     * <p>
     * 🔴 总量超限时<b>丢弃后面的条目并明确告知</b>，而不是把每条都截短：
     * 半截的文章对模型没有价值（它可能据此编造后半段），而"没给"是可以应对的事实。
     */
    static String format(List<Doc> docs, int maxPerUrl, int maxTotal) {
        if (docs == null || docs.isEmpty()) {
            return "error:没有拿到任何正文内容。请换一个 URL 重试，或改用你已知的信息回答。";
        }
        StringBuilder sb = new StringBuilder();
        int budget = maxTotal > 0 ? maxTotal : Integer.MAX_VALUE;
        int shown = 0;
        StringBuilder tail = new StringBuilder();
        for (Doc doc : docs) {
            if (!doc.ok()) {
                sb.append("- ").append(doc.url())
                        .append("  ✗ ").append(doc.error()).append("\n");
                continue;
            }
            String content = doc.content() == null ? "" : doc.content().strip();
            String clipped = content.length() > maxPerUrl
                    ? content.substring(0, maxPerUrl) + "\n……（已达单条上限 " + maxPerUrl + " 字符，内容被截断）"
                    : content;
            if (clipped.length() > budget) {
                tail.append("\n以下 ")
                        .append(docs.size() - shown)
                        .append(" 条因总字数上限（").append(maxTotal).append("）被省略，"
                                + "如需查看请单独再调用一次 web_extract：");
                for (int i = shown; i < docs.size(); i++) {
                    tail.append("\n  - ").append(docs.get(i).url());
                }
                break;
            }
            budget -= clipped.length();
            shown++;
            sb.append("[源 ").append(shown).append("] ").append(doc.url()).append("\n")
                    .append(clipped.isBlank() ? "（该页面没有提取到正文）" : clipped)
                    .append("\n\n");
        }
        String tailText = tail.length() > 0 ? tail.toString().strip() : "";
        String rendered = sb.toString().strip();
        String combined = java.util.stream.Stream.of(rendered, tailText)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (combined.isEmpty()) {
            return "error:没有拿到任何可用的正文内容。请换一个 URL 重试。";
        }
        if (shown == 0) {
//        🔴 一条都没成功时，sb 里攒的正是「每个 URL 各自为什么失败」—— 必须照样交出去。
//        只回一句笼统的 error 会让模型无从应对（它不知道该换 URL 还是换工具），
//        而这恰恰是本工具最重要的输出（partial success 是本端点的常态）。
            return "error:本次没有抓到任何可用正文。\n" + combined;
        }
        return combined;
    }

    private static String textOf(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** 只为了把 Jackson 的受检异常收敛在内部，避免每个调用点都 try/catch */
    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        String write(Object obj) throws Exception {
            return mapper.writeValueAsString(obj);
        }

        com.fasterxml.jackson.databind.JsonNode read(String s) throws Exception {
            return mapper.readTree(s);
        }
    }
}
