package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.SysFile;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: AI 产出物（artifact）的落库（P2-10）。
 * <p>
 * 为什么单独一个 service 而不塞进 {@code FileService}：那条线管的是**用户上传**的附件
 * （知识库文件、聊天附件，biz_type=KNOWLEDGE/CHAT），而这里是 **AI 产出**的交付物
 * （biz_type=ARTIFACT，带 session_id）。两者的生命周期与清理策略不同，后续要加
 * "按会话列产物 / 删产物"也只会动这里。
 */
public interface ArtifactService {

    /**
     * 把工具产出的 artifact 落库。
     *
     * @param artifact  工具返回的 artifact 结构：{@code {name, url, size, extension, sourcePath}}
     * @param userId    归属用户（必填 —— 库里 user_id 是 NOT NULL，拿不到时不落库）
     * @param sessionId 归属会话，用于按会话追溯
     * @param runId     <b>产出该文件那次运行</b>的 trace_id（产物归属，方案 B）。
     *                  <p>
     *                  它会被写进 {@code sys_file.run_id}，前端拿它与
     *                  {@code GET /api/history/{sessionId}} 每行上的 {@code runId} 做<b>字符串相等</b>匹配，
     *                  从而把产物内联到「产出它的那一轮回答」末尾 —— 这是刷新页面后唯一可靠的归属依据。
     *                  </p>
     *                  <p>
     *                  可空：非流式/拿不到 runId 的场景传 null，产物仍会落库，只是归属不明
     *                  （前端放进「成果文件」面板，不会冒充某一轮产出的）。
     *                  </p>
     * @return 落库后的记录（含生成的 id），供 SSE 事件带上前端；跳过落库时返回 null
     */
    SysFile save(Map<String, Object> artifact, Long userId, String sessionId, String runId);

    /**
     * 列出某个会话里的全部产物（按时间倒序）。
     * <p>
     * **必须带 userId 过滤** —— 这是越权防护（详见 `AGENTS.md §12.1` 的 IDOR 教训）：
     * sessionId 是客户端传的，不校验归属就等于任何人拿到会话 ID 就能列出别人的产物。
     *
     * @return 产物列表；参数缺失时返回空列表（不抛异常，便于前端直接渲染"暂无产物"）
     */
    List<SysFile> listBySession(String sessionId, Long userId);

    /**
     * 删除一条文件记录：**先删记录、再尽力删 OSS 对象**。
     * <p>
     * 顺序是刻意的：用户点"删除"的语义以记录为准，对象残留只是存储成本；
     * 反过来（先删对象再删记录）一旦记录删除失败，用户会看到一个点开就 404 的产物。
     * <p>
     * 🔴 <b>2026-10-05 变更：不再限定 {@code biz_type=ARTIFACT}。</b>
     * 前端「文件与产物」是统一视图（一份列表同时含用户上传的 {@code CHAT} 附件与
     * AI 产物 {@code ARTIFACT}），删除按钮只有一个。加了 bizType 条件后，
     * 附件一律删不掉，用户只看到「产物不存在或无权删除」，完全不知道原因。
     * 归属校验靠 {@code user_id} 就够了 —— bizType 从来不是安全边界。
     * <p>
     * 语义更准确的入口是 {@link FileService#delete}，两者行为一致。
     *
     * @return true = 确实删掉了一条；false = 不存在 / 不属于该用户。
     *         **两种情况不区分**，避免通过返回值探测他人文件是否存在
     */
    boolean delete(Long id, Long userId);
}
