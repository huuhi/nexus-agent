package com.huzhijian.nexusagentweb.mcp;

import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.McpInformationServiceImpl;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: MCP 客户端的生命周期管理。
 * <p>
 * 解决的问题：原实现在每次对话时都为每个 MCP 服务**新建** {@code DefaultMcpClient}，
 * 而且健康检查通过的分支**从不关闭** —— 每轮对话泄漏一批连接与相关线程，
 * 长时间运行必然出问题。同时「连不上」的分支只在异常里更新 available，
 * 标记逻辑还写错了层级。
 * <p>
 * 现在的行为：
 * <ul>
 *   <li>按 mcpId 缓存客户端，同一服务全程只建立一次连接</li>
 *   <li>创建时做一次健康检查；失败则立即关闭并返回 null（调用方据此把该服务标记为不可用）</li>
 *   <li>配置变更（改 URL / header、删除）时剔除对应客户端，下次重新连接</li>
 *   <li>应用关闭时统一关闭（{@link PreDestroy}）</li>
 * </ul>
 * <p>
 * 🔴 <b>2026-10-09 补充：失效客户端是怎么被发现的</b>
 * <p>
 * 复用时不重复做健康检查（每轮对话多一次网络往返不值当），所以「客户端建好之后才失效」
 * （token 过期 / 被撤销 / 远端重启换鉴权）必须另有出口。原来这个出口是**空的** ——
 * langchain4j 的 `autoHealthCheck` 默认开启、30 秒一次、失败就永久重连，
 * 而它只会刷日志：既不会剔除缓存，也不会更新 {@code available}。
 * 结果是失效的客户端<b>永久赖在缓存里</b>（每轮对话都拿它、每次调用都失败，直到重启进程），
 * 同时每 30 秒往日志里丢一次完整堆栈。
 * <p>
 * 现在改成：
 * <ol>
 *   <li>关掉 langchain4j 的自动健康检查（见 {@link #create} 里的说明），日志不再被刷；</li>
 *   <li>挂 {@link McpClientListener#onExecuteToolError} —— <b>工具调用失败即剔除该客户端</b>，
 *       下一轮对话重新建连并做一次 {@code checkHealth()}；
 *       仍失败就返回 null，调用方把 {@code available} 打成 false 并注入提示词，用户可见。</li>
 * </ol>
 * ⚠️ 这里的失败回调只在**异常级**触发（传输/协议/鉴权），工具业务层返回
 * {@code isError=true} 走的是 {@code afterExecuteTool}，不会误伤正常报错的工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientRegistry {

    private final AgentProperties agentProperties;

    /**
     * 出网地址校验（SSRF 防护）。
     * <p>
     * ⚠️ <b>2026-10-04 新增</b>：修复前这里对用户填的 URL 零校验，
     * {@code checkHealth()} 直接让服务端连过去，等于让任意登录用户
     * 用本服务探测内网 / 读取云元数据。
     * <p>
     * 刻意在<b>建连前</b>再校验一次（而不是只在保存配置时校验）：
     * 库里可能存着加 UrlGuard 之前写入的旧数据，只在写入侧校验会漏掉它们。
     */
    private final UrlGuard urlGuard;

    /** key = mcp_information.id */
    private final Map<Long, McpClient> clients = new ConcurrentHashMap<>();

    /**
     * 取一个可用的 MCP 客户端：命中缓存则复用，否则新建并做健康检查。
     *
     * @return 不可用时返回 null（调用方应把该服务标记为 available=false）
     */
    public McpClient getOrCreate(McpInformation info) {
        if (!agentProperties.getMcp().isCacheClients()) {
            return create(info);
        }
        McpClient cached = clients.get(info.getId());
        if (cached != null) {
            return cached;
        }
        McpClient created = create(info);
        if (created != null) {
            clients.put(info.getId(), created);
        }
        return created;
    }

    /**
     * 剔除并关闭某个 MCP 客户端。用于配置变更或已确认不可用时。
     */
    public void evict(Long mcpId) {
        if (mcpId == null) {
            return;
        }
        McpClient removed = clients.remove(mcpId);
        if (removed != null) {
            log.debug("剔除 MCP 客户端 id={}", mcpId);
            closeQuietly(removed);
        }
    }

    public int cachedCount() {
        return clients.size();
    }

    @PreDestroy
    public void closeAll() {
        if (clients.isEmpty()) {
            return;
        }
        log.info("应用关闭，关闭 {} 个 MCP 客户端", clients.size());
        clients.values().forEach(this::closeQuietly);
        clients.clear();
    }

    private McpClient create(McpInformation info) {
        // ⚠️ 出网前必须校验 URL（SSRF 防护）。抛异常而非返回 null：
        // 地址有问题属于「配置不合法」，与「连不上」是两回事，不该被静默降级成 available=false，
        // 否则用户会以为服务不可用，而真正的问题是地址指向内网被拦了。
        urlGuard.validate(info.getUrl(), "MCP 服务地址（" + info.getName() + "）");

        // ⚠️ 2026-10-04 修复：header 之前**存了但从来没发出去** ——
        // 用户在配置里填的 Authorization / X-Api-Key 全部被丢弃，
        // 于是「配了鉴权的 MCP 服务」必然 checkHealth 失败、被标成 available=false，
        // 而日志里只说"连不上"，用户完全看不出是鉴权头没带上。
        Map<String, String> headers = McpInformationServiceImpl.parseHeader(info.getHeader());

        StreamableHttpMcpTransport.Builder builder = StreamableHttpMcpTransport.builder()
                .url(info.getUrl())
                .timeout(agentProperties.getMcp().getHealthTimeout());
        if (!headers.isEmpty()) {
            builder.customHeaders(headers);
            // 只打头名不打值：值通常是凭据
            log.debug("MCP 客户端携带自定义头：id={} 头名={}", info.getId(), headers.keySet());
        }

        StreamableHttpMcpTransport transport = builder.build();

        // 监听器里要能引用"这个客户端自己"，而 build() 返回后才有引用，故先用一个一格数组装
        McpClient[] self = new McpClient[1];
        McpClient client = null;
        // 🔴 try 必须从 build() 就开始，不能只包 checkHealth()（2026-10-09 测出来的）：
        // DefaultMcpClient 的**构造器里就会做 MCP 握手**（initialize），
        // 所以「服务连不上 / 返回 401」是在 build() 这一行抛的，不是在 checkHealth()。
        // 原来 try 只包住 checkHealth()，于是这类异常会直接穿过 create()
        // → 冒到 getMcp() → 把**整个聊天请求打成 500**。
        // 而调用方（McpInformationServiceImpl）的设计预期明明是「连不上就标记 available=false
        // 并注入提示词」，也就是一个坏掉的 MCP 只该让那一个服务不可用，不该拖垮整场对话。
        try {
            client = DefaultMcpClient.builder()
                    .transport(transport)
                    // 🔴 自报家门（2026-10-09）：这个 key 会出现在 langchain4j **自己的日志**里，
                    // 比如「MCP server health check (client key: ...) failed」。
                    // 不设它的话 langchain4j 会生成一个随机 UUID，日志与库里的配置行对不上，
                    // 用户拿着日志根本不知道是哪个 MCP 服务在报错（真实故障现场踩过）。
                    .key(clientKey(info))
                    // 🔴 关掉 langchain4j 的自动健康检查（2026-10-09）。
                    // 它默认是**开启**的、间隔 **30 秒**，失败就打完整堆栈 + 重连、**永不放弃**。
                    // 对一个"token 过期"这种只能人工修的故障，这只是每 30 秒刷一次栈，毫无价值。
                    // 关掉不会失去恢复能力：失效由下面的 listener 捕获并剔除缓存，
                    // 下次对话重新建连时 checkHealth() 会把 available 打成 false（用户可见）；
                    // 而传输层的 SSE 子通道重连是独立机制，不受这里影响。
                    .autoHealthCheck(false)
                    .listener(new McpClientListener() {
                        @Override
                        public void onExecuteToolError(McpCallContext context, Throwable error) {
                            // 工具调用失败 = 这个客户端可能已经失效（token 过期/被撤销/远端重启）。
                            // 剔除它，别让它永久赖在缓存里 —— 否则每轮对话都拿它、每次都失败，
                            // 而 getOrCreate() 命中缓存时是不做健康检查的，自己永远发现不了。
                            evictSelf(info.getId(), self[0], error);
                        }
                    })
                    .build();
            self[0] = client;
            client.checkHealth();
            log.debug("MCP 客户端就绪：id={} name={}", info.getId(), info.getName());
            return client;
        } catch (Exception e) {
            // 连不上必须把刚建的连接关掉，否则每失败一次就漏一个 HttpClient/线程。
            // 注意 client 为 null 时（build 阶段就抛了，还没拿到客户端）要手动关 transport ——
            // 它是上面就建好的，不会被 client.close() 覆盖到。
            log.warn("MCP 不可用，已关闭连接：id={} name={} url={} 原因={}",
                    info.getId(), info.getName(), info.getUrl(), rootMessage(e));
            if (client != null) {
                closeQuietly(client);
            } else {
                closeQuietly(transport);
            }
            return null;
        }
    }

    /**
     * 客户端标识：{@code mcp-<库里的 id>:<服务名>}。
     * <p>
     * 它会进 langchain4j 自己的日志，所以必须**能一眼对回配置行**；同时要克制：
     * 只取库里的自增 id 与用户填的名字，**不带 URL、不带 header**（header 里通常是凭据）。
     * <p>
     * 包级可见是为了让测试能直接断言这个形态，不必起一个真的 MCP 服务。
     */
    static String clientKey(McpInformation info) {
        String name = info.getName() == null ? "" : info.getName().replaceAll("\\s+", " ").strip();
        if (name.length() > 30) {
            name = name.substring(0, 30) + "…";
        }
        return "mcp-" + info.getId() + (name.isEmpty() ? "" : ":" + name);
    }

    /**
     * 把「工具调用刚失败的那个客户端」从缓存里剔除并关闭。
     * <p>
     * 用 {@code remove(key, value)} 而不是 {@code remove(key)}：它是<b>原子</b>的，
     * 只有缓存里**还是它自己**时才移除。这一条同时解决两个问题：
     * <ul>
     *   <li>同一次请求里并发失败的多个工具调用，只有第一个真的关（其余发现已经被移除，直接返回）；</li>
     *   <li>如果失败回调来得晚，而缓存里已经换成配置变更后新建的客户端，
     *       不会误关那个新的。</li>
     * </ul>
     */
    private void evictSelf(Long mcpId, McpClient self, Throwable error) {
        if (mcpId == null || self == null || !clients.remove(mcpId, self)) {
            return;
        }
        log.warn("MCP 工具执行失败，已剔除该客户端（下次对话会重新建连并做一次健康检查，"
                + "仍失败则标记为不可用）：id={} 原因={}", mcpId, rootMessage(error));
        closeQuietly(self);
    }

    /** 取最内层的原因：外层多是 ExecutionException / RuntimeException 这种没有信息量的包装 */
    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "(无异常信息)";
        }
        Throwable deepest = error;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return deepest.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** 关闭失败不抛出：它只在清理路径上被调用，抛出去只会掩盖真正的问题 */
    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 关掉尚未交给客户端的传输层。
     * <p>
     * 用在「{@code DefaultMcpClient} 的 build/握手阶段就抛异常」这条路上 ——
     * 那时 transport 已经建好（占着一个 JDK HttpClient），但还没有 client 能替我们关它。
     */
    private void closeQuietly(McpTransport transport) {
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 传输层失败（忽略）：{}", e.getMessage());
        }
    }

    /** 当前缓存的客户端（供监控/排查） */
    public List<Long> cachedIds() {
        return new ArrayList<>(clients.keySet());
    }
}
