package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.dto.ChatDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatService {
    SseEmitter chat(ChatDTO chatDTO);

    /**
     * 查询某个 API 配置下可用的模型列表。
     * <p>
     * 🔴 <b>2026-10-05 签名变更：入参从 (baseUrl, token) 改为 (configId)。</b>
     * 旧签名让前端回传密钥，而前端手上的 Key 是 {@code UserConfigServiceImpl.decryptKey()}
     * 拼出来的**打码值**（{@code sk****3t5d}），拿去调厂商必然 401。
     * 现在 baseUrl 与明文 Key 都由服务端按 id 自行查库解密 ——
     * 与对话 / 乐享 RAG / MCP 三处的做法终于一致了。
     * <p>
     * 实现约定：
     * <ul>
     *   <li>配置不存在 / 不属于当前用户 → 抛 {@code ValidationException}（4xx，不是 500）；</li>
     *   <li>厂商不支持 {@code /v1/models}（DeepSeek、小米等）或 Key 不对 →
     *       <b>降级</b>返回该配置里用户已保存的模型名，不抛异常；</li>
     *   <li>出网前用 {@code UrlGuard} 复核 baseUrl，防内网地址。</li>
     * </ul>
     *
     * @param configId {@code user_config.llm_api_token} 里那条 API 配置的 id
     * @return 模型名列表；**可能为空列表**（该配置下没存过任何模型且厂商不支持列表接口）
     */
    List<String> getModelList(String configId);
}
