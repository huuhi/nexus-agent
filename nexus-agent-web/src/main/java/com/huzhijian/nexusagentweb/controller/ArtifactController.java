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
     * 删除一个产物。
     * <p>
     * 删除语义：先删数据库记录、再尽力删 OSS 对象。记录不存在或不属于当前用户时，
     * 返回统一的错误提示（**不区分**这两种情况，避免探测他人产物是否存在）。
     */
    @Operation(summary = "删除产物（先删记录再尽力删 OSS 对象）")
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
