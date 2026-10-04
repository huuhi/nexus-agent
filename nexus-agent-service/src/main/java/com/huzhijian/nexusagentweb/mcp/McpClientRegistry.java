package com.huzhijian.nexusagentweb.mcp;

import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
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
 *   <li>配置变更（改 URL / 删除）时剔除对应客户端，下次重新连接</li>
 *   <li>应用关闭时统一关闭（{@link PreDestroy}）</li>
 * </ul>
 * <p>
 * 复用时不重复做健康检查（避免每轮对话多一次网络往返）；若远端会话失效，
 * 工具调用会失败并把错误回给模型，由用户或后续的健康检查机制处理。
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

        StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                .url(info.getUrl())
                .timeout(agentProperties.getMcp().getHealthTimeout())
                .build();
        McpClient client = DefaultMcpClient.builder()
                .transport(transport)
                .build();
        try {
            client.checkHealth();
            log.debug("MCP 客户端就绪：id={} name={}", info.getId(), info.getName());
            return client;
        } catch (Exception e) {
            // 连不上必须把刚建的客户端关掉，否则又是一次泄漏
            log.warn("MCP 不可用，已关闭连接：id={} name={} url={} 原因={}",
                    info.getId(), info.getName(), info.getUrl(), e.getMessage());
            closeQuietly(client);
            return null;
        }
    }

    /** 关闭失败不抛出：它只在清理路径上被调用，抛出去只会掩盖真正的问题 */
    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败（忽略）：{}", e.getMessage());
        }
    }

    /** 当前缓存的客户端（供监控/排查） */
    public List<Long> cachedIds() {
        return new ArrayList<>(clients.keySet());
    }
}
