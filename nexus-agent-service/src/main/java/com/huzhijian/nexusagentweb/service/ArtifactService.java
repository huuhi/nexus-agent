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
     * @return 落库后的记录（含生成的 id），供 SSE 事件带上前端；跳过落库时返回 null
     */
    SysFile save(Map<String, Object> artifact, Long userId, String sessionId);

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
     * 删除一个产物：**先删记录、再尽力删 OSS 对象**。
     * <p>
     * 顺序是刻意的：用户点"删除"的语义以记录为准，对象残留只是存储成本；
     * 反过来（先删对象再删记录）一旦记录删除失败，用户会看到一个点开就 404 的产物。
     *
     * @return true = 确实删掉了一条；false = 不存在 / 不属于该用户。
     *         **两种情况不区分**，避免通过返回值探测他人产物是否存在
     */
    boolean delete(Long id, Long userId);
}
