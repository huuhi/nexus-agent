package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 查询「某个 API 配置下可用模型列表」的请求体。
 * <p>
 * 🔴 <b>2026-10-05 契约变更：只传 {@code configId}，不再传 baseUrl / token。</b>
 *
 * <h3>为什么改</h3>
 * 原实现要前端回传 {@code baseUrl} + {@code token} 两个字段，是**全项目唯一一个
 * 让前端持有密钥的接口** —— 对话（{@code ChatContextFactory}）、乐享 RAG
 * （{@code LexiangServiceImpl}）、MCP（{@code McpInformationServiceImpl}）
 * 全部都是后端自己查库 + {@code EncryptorFactory.decrypt()}，只有这里例外。
 * <p>
 * 前端手上根本没有明文 Key：{@code UserConfigServiceImpl.decryptKey()} 返回给前端的
 * 是**打码值**（形如 {@code sk****3t5d}，把明文 substring 拼出来的），
 * 拿它去请求厂商必然 401。这不是前端传错了，是契约本身错了。
 *
 * <h3>顺带堵掉一个 SSRF + 凭据外送</h3>
 * 旧契约里 {@code baseUrl} 由前端指定。一旦改成后端解密出**明文 Key** 再配合前端指定的地址，
 * 就等于：任何登录用户都能让服务端带着**真实凭据**去请求任意 URL
 * （把 baseUrl 改成攻击者站点即可把 Key 送出去）。
 * 现在 {@code baseUrl} 与 Key 都只从库里按 id 取，
 * 且出网前仍走 {@code UrlGuard} 复核 —— 用户改过的地址不能指向内网。
 *
 * @author 胡志坚
 * @version 1.1.0
 * 创造日期 2026/9/23，2026/10/5 改为只传 configId
 */
public record ModelListDTO(
        /**
         * {@code user_config.llm_api_token} 里那条 API 配置的 id
         * （即 {@code APIConfig.id}，保存/更新 API 配置时由后端生成）。
         * <p>
         * 后端据此取出该配置的 baseUrl 与**解密后的**明文 Key。
         */
        @NotBlank(message = "configId 不能为空！") String configId) {
}
