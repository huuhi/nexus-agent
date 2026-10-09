package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网页正文提取（{@code web_extract}）的行为测试。
 * <p>
 * 用一个本地 {@link HttpServer} 假扮 Tavily —— 相比 mock HttpClient：
 * <b>连请求体的形态、状态码分支、partial success 的渲染都能真跑一遍</b>，
 * 而 mock 只能验证「我们按自己假设的协议调了自己写的桩」。
 *
 * @see WebExtractTool
 */
@DisplayName("web_extract —— 正文提取：SSRF 闸门、partial success、上下文护栏")
class WebExtractToolTest {

    private static final String OK_URL_1 = "http://example.com/a";
    private static final String OK_URL_2 = "http://example.org/b";

    // ============ 1. 工具签名：List<String> 能不能真的生成 array schema ============

    @Test
    @DisplayName("🔴 tool schema：urls 参数必须是 array(string)，否则模型根本不知道可以传多个")
    void urlsParamIsArrayInToolSchema() {
        ToolSpecification spec = ToolSpecifications
                .toolSpecificationsFrom(WebExtractTool.class).stream()
                .filter(s -> "web_extract".equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有生成 web_extract 的工具规格"));

        JsonObjectSchema params = spec.parameters();
        assertNotNull(params, "web_extract 应有参数 schema");

        Object urls = params.properties().get("urls");
        assertNotNull(urls, "缺少 urls 参数 —— schema 生成失败的话模型会拿不到这个入参");

        var arraySchema = assertInstanceOf(JsonArraySchema.class, urls,
                "urls 必须是 array —— 若是 string，模型只会传单个 URL，批量能力等于没接");
        assertTrue(String.valueOf(arraySchema.items()).toLowerCase().contains("string"),
                () -> "urls 数组的元素类型必须是 string，实际是：" + arraySchema.items());

//        反向验证：同一套断言换成「单 String 参数」的工具应该是 STRING 而不是 ARRAY，
//        否则这条断言是恒真的 —— 证明它真的在区分形态，而不是随手 assertTrue。
        ToolSpecification searchSpec = ToolSpecifications
                .toolSpecificationsFrom(WebSearchTool.class).stream()
                .filter(s -> "web_search".equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有生成 web_search 的工具规格"));
        Object query = searchSpec.parameters().properties().get("query");
        assertFalse(query instanceof dev.langchain4j.model.chat.request.json.JsonArraySchema,
                "反向验证失效：web_search 的 query 是单字符串，不该被判成数组类型 ——"
                        + "若它也是数组说明上面的断言没有区分力");
    }

    // ============ 2. 启用条件 ============

    @Test
    @DisplayName("未配置 TAVILY_API_KEY → 工具集不注册（与 web_search 同一套 Key，独立开关会漏）")
    void notEnabledWithoutKey() {
        assertFalse(tool(null, "http://127.0.0.1:9/x").enabled(null));
        assertFalse(tool("   ", "http://127.0.0.1:9/x").enabled(null));
        assertTrue(tool("tvly-test-key", "http://127.0.0.1:9/x").enabled(null));
    }

    // ============ 3. SSRF：内网 URL 一个都不许出网 ============

    @Test
    @DisplayName("🔴 内网/本机 URL 被 UrlGuard 拦下，一个请求都没发")
    void privateUrlsAreRejectedBeforeAnyRequest() throws IOException {
        try (FakeTavily fake = startFakeTavily(200, "{\"results\":[]}")) {
            WebExtractTool t = tool("tvly-test-key", fake.endpoint());

            String out = t.webExtract("s1", List.of("http://127.0.0.1:8080/admin",
                    "http://192.168.1.10/", "http://10.0.0.5:8888/x"));

            assertNull(fake.receivedBody().get(),
                    "内网 URL 竟然触发了出网请求 —— SSRF 防线被绕过了");
            assertTrue(out.startsWith("error:"), () -> "实际：" + out);
            assertTrue(out.contains("内网"), () -> "错误信息要说清楚是为什么被拒：" + out);
        }
    }

    @Test
    @DisplayName("非 http/https 协议同样拒绝（file/ftp 能被用来读本地文件）")
    void nonHttpSchemeRejected() throws IOException {
        try (FakeTavily fake = startFakeTavily(200, "{\"results\":[]}")) {
            WebExtractTool t = tool("tvly-test-key", fake.endpoint());
            String out = t.webExtract("s1", List.of("file:///etc/passwd"));
            assertNull(fake.receivedBody().get(), "file:// 竟然出网了");
            assertTrue(out.startsWith("error:"), () -> "实际：" + out);
        }
    }

    // ============ 4. 请求体符合 Tavily /extract 契约 ============

    @Test
    @DisplayName("请求体：urls 为数组、basic 深度、markdown 格式（对齐「5 个 URL = 1 credit」的计费口径）")
    void requestBodyMatchesTavilyContract() throws IOException {
        try (FakeTavily fake = startFakeTavily(200, "{\"results\":[{\"url\":\"" + OK_URL_1
                + "\",\"raw_content\":\"hello\"}]}")) {
            WebExtractTool t = tool("tvly-test-key", fake.endpoint());
            t.webExtract("s2", List.of(OK_URL_1));

            String body = fake.receivedBody().get();
            assertNotNull(body, "没有发出请求");
            assertTrue(body.contains("\"urls\""), () -> "请求体缺少 urls：" + body);
            assertTrue(body.contains("\"extract_depth\":\"basic\""),
                    () -> "默认必须是 basic —— advanced 单价翻倍，不能默认：" + body);
            assertTrue(body.contains("\"format\":\"markdown\""), () -> "实际：" + body);
            assertTrue(body.contains("\"urls\":["), () -> "urls 必须是数组，实际：" + body);
        }
    }

    @Test
    @DisplayName("URL 数量封顶：超过上限的不进请求，并在结果里说明被跳过")
    void urlCountIsCapped() throws IOException {
        try (FakeTavily fake = startFakeTavily(200, "{\"results\":[]}")) {
            WebExtractTool t = tool("tvly-test-key", fake.endpoint());
            List<String> many = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                many.add("http://example.com/p" + i);
            }
            String out = t.webExtract("s3", many);

            String body = fake.receivedBody().get();
            assertNotNull(body);
            assertEquals(5, countOccurrences(body, "http://example.com/p"),
                    "默认上限是 5（恰好一个计费单位），实际请求里带了这么多：" + body);
            assertTrue(out.contains("超出单次调用上限"), () -> "被跳过的 URL 必须告诉模型，实际：" + out);
        }
    }

    // ============ 5. partial success 是常态，必须逐个回报 ============

    @Test
    @DisplayName("🔴 HTTP 200 也可能一条都没抓到：results 与 failed_results 都要渲染出来")
    void partialSuccessRendersBothSides() throws IOException {
        String resp = """
                {"results":[{"url":"%s","raw_content":"这是正文内容"},
                            {"url":"%s","raw_content":"第二条正文"}],
                 "failed_results":[{"url":"http://example.com/dead","error":"Failed to retrieve content"}]}
                """.formatted(OK_URL_1, OK_URL_2);

        try (FakeTavily fake = startFakeTavily(200, resp)) {
            WebExtractTool t = tool("tvly-test-key", fake.endpoint());
            String out = t.webExtract("s4", List.of(OK_URL_1, OK_URL_2, "http://example.com/dead"));

            assertTrue(out.contains("[源 1]"), () -> "成功条目要带序号（模型据此引用），实际：" + out);
            assertTrue(out.contains("这是正文内容"), () -> "实际：" + out);
            assertTrue(out.contains("第二条正文"), () -> "实际：" + out);
            assertTrue(out.contains("http://example.com/dead"), () -> "失败条目要出现，实际：" + out);
            assertTrue(out.contains("Failed to retrieve content"), () -> "失败原因要带出来，实际：" + out);
            assertFalse(out.startsWith("error:"),
                    "有成功结果时不该整体报 error —— 那会让模型以为工具坏了从而放弃，实际：" + out);
        }
    }

    // ============ 6. 上下文护栏 ============

    @Test
    @DisplayName("单条超长正文按字符数截断，并标注被截断（防止一个网页顶穿上下文）")
    void longContentIsTruncatedPerUrl() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getWebsearch().setExtractMaxCharsPerUrl(20);
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("很长的一段正文");
        }
        String resp = "{\"results\":[{\"url\":\"" + OK_URL_1 + "\",\"raw_content\":\"" + longContent + "\"}]}";

        try (FakeTavily fake = startFakeTavily(200, resp)) {
            WebExtractTool t = new WebExtractTool(props, new UrlGuard(false), mock(ToolCallGuard.class),
                    "tvly-test-key", fake.endpoint());
            String out = t.webExtract("s5", List.of(OK_URL_1));

            assertTrue(out.contains("内容被截断"), () -> "必须告诉模型内容是截断的，实际：" + out);
            assertTrue(out.length() < 400, () -> "截断没生效，输出长度 " + out.length());
        }
    }

    @Test
    @DisplayName("总量超限时丢弃后面的条目并明确列出哪些被省略（而不是把每条都截短）")
    void totalBudgetDropsLaterDocsExplicitly() throws IOException {
        AgentProperties props = new AgentProperties();
        props.getWebsearch().setExtractMaxTotalChars(50);
        String resp = """
                {"results":[{"url":"http://example.com/a","raw_content":"%s"},
                            {"url":"http://example.com/b","raw_content":"%s"}]}
                """.formatted("A".repeat(40), "B".repeat(40));

        try (FakeTavily fake = startFakeTavily(200, resp)) {
            WebExtractTool t = new WebExtractTool(props, new UrlGuard(false), mock(ToolCallGuard.class),
                    "tvly-test-key", fake.endpoint());
            String out = t.webExtract("s6", List.of("http://example.com/a", "http://example.com/b"));

            assertTrue(out.contains("被省略"), () -> "实际：" + out);
            assertTrue(out.contains("http://example.com/b"),
                    () -> "被省略的条目要点名，让模型知道还能再取，实际：" + out);
        }
    }

    // ============ 7. 失败分支要给模型可读的话 ============

    @Test
    @DisplayName("401/403 → 明确说 Key 无效；429 → 明确说限流，且都要求别重复调用")
    void authAndRateLimitErrorsAreActionable() throws IOException {
        try (FakeTavily auth = startFakeTavily(401, "{\"error\":\"unauthorized\"}")) {
            String out = tool("tvly-test-key", auth.endpoint()).webExtract("s7", List.of(OK_URL_1));
            assertTrue(out.contains("TAVILY_API_KEY 无效"), () -> "实际：" + out);
            assertTrue(out.contains("请勿重复调用"), () -> "实际：" + out);
        }
        try (FakeTavily rate = startFakeTavily(429, "{\"error\":\"rate limited\"}")) {
            String out = tool("tvly-test-key", rate.endpoint()).webExtract("s8", List.of(OK_URL_1));
            assertTrue(out.contains("限流"), () -> "实际：" + out);
        }
    }

    // ============ 内部工具 ============

    private static WebExtractTool tool(String envKey, String endpoint) {
        ToolCallGuard guard = mock(ToolCallGuard.class);
        when(guard.interceptText(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        return new WebExtractTool(new AgentProperties(), new UrlGuard(false), guard, envKey, endpoint);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** 起一个本地 http 服务假扮 Tavily，记录收到的请求体，便于断言"有没有真的发出请求" */
    private static FakeTavily startFakeTavily(int status, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/extract", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return new FakeTavily(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/extract", received);
    }

    private record FakeTavily(HttpServer server, String endpoint, AtomicReference<String> receivedBody)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
