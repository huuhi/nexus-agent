package com.huzhijian.nexusagentweb.em;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明:
 */
public enum BizType {
    KNOWLEDGE,
    CHAT,
    /**
     * AI 产出的「交付物」（P2-10）：由沙盒/工具生成、上传 OSS 后作为产物交给用户下载。
     * <p>
     * 与 {@link #CHAT}（用户上传的聊天附件）区分开：产物是 AI 给的，附件是用户给的，
     * 前端展示与清理策略都不同。
     */
    ARTIFACT;
}
