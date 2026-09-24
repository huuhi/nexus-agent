package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.SysFile;

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
}
