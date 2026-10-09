package com.huzhijian.nexusagentweb.mcp;

import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-09「MCP 健康检查 401 刷屏 + 失效客户端永久驻留」的护栏。
 * <p>
 * <b>事故现象</b>（用户贴的服务器日志）：
 * <pre>
 * DefaultMcpClient : MCP server health check (client key: 34b4aed1-a101-41ad-afcd-0277b73722cc)
 *                    failed. Attempting to reconnect...
 * Caused by: RuntimeException: Unexpected status code: 401
 * </pre>
 * <b>三个根因</b>：
 * <ol>
 *   <li><b>看不出是哪个服务</b>：我们没给 {@code DefaultMcpClient} 设 {@code key}，
 *       langchain4j 就自己生成一个随机 UUID，日志与库里的配置行对不上 —— 拿着日志无从下手。
 *       → 现在设成 {@code mcp-<id>:<名字>}，见 {@link McpClientRegistry#clientKey}。</li>
 *   <li><b>失效客户端永久驻留 + 日志被刷</b>：{@code autoHealthCheck} 默认开启、30 秒一次、
 *       失败就永久重连，而它只刷日志，既不剔除缓存也不更新 {@code available}；
 *       同时 {@code getOrCreate()} 命中缓存时**不做健康检查**，自己永远发现不了客户端已失效。
 *       → 现在关掉自动健康检查，改由 {@code onExecuteToolError} 剔除缓存。</li>
 *   <li><b>连不上时异常会冒到聊天请求上</b>：{@code DefaultMcpClient} 的<b>构造器里就做 MCP 握手</b>，
 *       所以「服务连不上 / 返回 401」是在 {@code build()} 抛的，而原来 try 只包住
 *       {@code checkHealth()} —— 异常穿过 {@code create()} 一路冒到 {@code getMcp()}，
 *       把**整个聊天请求打成 500**，与调用方「连不上就标记 available=false 并注入提示词」的
 *       设计预期正好相反。→ 现在 try 从 {@code build()} 就开始。</li>
 * </ol>
 * <b>为什么必须测「剔除」而不是只测「不刷日志」</b>：只把自动健康检查关掉，
 * 等于把一个「吵但会自愈」的问题换成一个「安静但永不恢复」的问题 —— 那更糟。
 * 所以本次两条要一起看：关掉自动重试，同时保证失败能被感知。
 * <p>
 * 本测试用 JDK 自带的 {@link HttpServer} 起一个**只回 401** 的假 MCP 服务，
 * 不需要外网、不需要真实 MCP 服务端。
 */
@DisplayName("MCP 客户端：日志可定位（clientKey）、连不上不留缓存")
class McpClientRegistryTest {

    /** 一个只管回 401 的假 MCP 服务：模拟「token 过期/没配鉴权」的真实形态 */
    private static HttpServer start401Server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] body = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static McpInformation mcp(long id, String name, String url) {
        return McpInformation.builder()
                .id(id)
                .name(name)
                .url(url)
                .header("{}")
                .userId(1L)
                .available(true)
                .build();
    }

    // ==================== 1. 日志可定位：client key 必须能对回配置行 ====================

    @Test
    @DisplayName("🔴 client key 里必须带库里的 id 与服务名 —— 否则日志只有随机 UUID，无从定位")
    void clientKeyCarriesIdAndName() {
        assertEquals("mcp-42:高德地图", McpClientRegistry.clientKey(mcp(42L, "高德地图", "https://x/mcp")));
    }

    @Test
    @DisplayName("名字里的换行/多余空白要拍平，超长要截断（它会进日志行）")
    void clientKeyIsLogSafe() {
        String messy = McpClientRegistry.clientKey(mcp(7L, "  某\n服务   名称  ", "https://x/mcp"));
        assertEquals("mcp-7:某 服务 名称", messy);
        assertTrue(!messy.contains("\n"), () -> "换行会让一条日志断成两行：实际=" + messy);

        String longName = McpClientRegistry.clientKey(mcp(8L, "名".repeat(200), "https://x/mcp"));
        assertTrue(longName.length() < 60, () -> "名字没截断，日志行会失控：长度=" + longName.length());
    }

    @Test
    @DisplayName("名字为空也不能拼出 mcp-9: 这种尾巴（形态要稳定，便于正则/检索）")
    void clientKeyWithoutName() {
        assertEquals("mcp-9", McpClientRegistry.clientKey(mcp(9L, null, "https://x/mcp")));
        assertEquals("mcp-9", McpClientRegistry.clientKey(mcp(9L, "   ", "https://x/mcp")));
    }

    // ==================== 2. 连不上：不留缓存、不留健康检查线程 ====================

    @Test
    @DisplayName("🔴 鉴权失败：返回 null（不能让异常冒出去）且不进缓存 —— 一个坏 MCP 不能拖垮整场对话")
    void unauthorizedServerIsNotCached() throws Exception {
        HttpServer server = start401Server();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            // allowPrivate=true：本地假服务在私网地址上，不加这行会被 SSRF 校验先拦掉
            McpClientRegistry registry = new McpClientRegistry(new AgentProperties(), new UrlGuard(true));

            // ⚠️ 这里断言的是「不抛异常、返回 null」，而不是「抛了某个异常」：
            // DefaultMcpClient 的**构造器里就做 MCP 握手**，所以 401 是在 build() 抛的，
            // 而原来 try 只包住 checkHealth() —— 异常会穿过 create() 冒到聊天请求上打成 500，
            // 与调用方「连不上就标记 available=false」的设计预期正好相反。
            assertNull(registry.getOrCreate(mcp(1L, "假的401服务", url)),
                    "checkHealth/build 失败必须被降级成 null（调用方据此把 available 打成 false）");
            assertEquals(0, registry.cachedCount(),
                    "失败的客户端绝不能进缓存 —— 它每次都会失败，留着只会让每轮对话都白撞一次");

            // 反向验证：再取一次仍然是 null（证明上面不是碰巧命中某个空实现）
            assertNull(registry.getOrCreate(mcp(1L, "假的401服务", url)));
            assertEquals(0, registry.cachedCount(), "连续两次都不能留下缓存");
        } finally {
            server.stop(0);
        }
    }
}
