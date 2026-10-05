package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.service.ArtifactService;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: AI 产出物（artifact）的会话级管理（P2-10，决策 D5 = 虚拟工作区）。
 * <p>
 * 对应前端"本会话的文件"面板：列出本会话 AI 交付过的文件、支持删除。
 * <p>
 * <b>越权防护</b>：{@code sessionId} 是客户端传来的，所以 Service 层的每个查询/删除
 * 都带 {@code user_id} 条件（见 `AGENTS.md §12.1` 的 IDOR 教训）。
 * 这里再显式校验一次登录态，避免"鉴权被关掉时"直接裸奔。
 */
@RestController
@RequestMapping("/api/artifact")
@Tag(name = "产物", description = "本会话 AI 交付的文件（虚拟工作区，决策 D5）")
public class ArtifactController {

    private final ArtifactService artifactService;

    public ArtifactController(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    /**
     * 列出某个会话里 AI 交付的全部产物（按时间倒序）。
     */
    @Operation(summary = "列出某会话的产物文件",
            description = "会话归属在 Service 层用 `user_id` 过滤（防 IDOR）；未登录直接 401。")
    @GetMapping
    public Result list(@RequestParam String sessionId) {
        return Result.ok(artifactService.listBySession(sessionId, currentUserId()));
    }

    /**
     * 删除一个文件记录（先删数据库记录、再尽力删 OSS 对象）。
     * <p>
     * 🔴 <b>2026-10-05 变更：不再限定 {@code biz_type=ARTIFACT}。</b>
     * 前端「文件与产物」是统一视图，删除按钮对所有行都打这个端点，
     * 而原先 Service 层硬加了 {@code biz_type=ARTIFACT} 条件 ——
     * 用户上传的对话附件（{@code CHAT}）因此永远删不掉，一律报
     * 「产物不存在或无权删除」。归属校验靠 {@code user_id} 就够了，
     * bizType 不是安全边界，加它只会误伤。
     * <p>
     * 语义上更推荐用 {@code DELETE /api/file/{id}}（文件名与行为一致），
     * 这个端点保留是为了不让已上线的前端调用直接失效 —— 两者现在等价。
     */
    @Operation(summary = "删除一条文件记录（不限产物，先删记录再尽力删 OSS 对象）")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        boolean deleted = artifactService.delete(id, currentUserId());
        return deleted ? Result.ok() : Result.error("产物不存在或无权删除");
    }

    private Long currentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录!");
        }
        return userId;
    }
}
