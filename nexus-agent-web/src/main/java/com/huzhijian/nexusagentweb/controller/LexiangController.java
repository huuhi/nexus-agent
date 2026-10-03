package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.LexiangCredential;
import com.huzhijian.nexusagentweb.dto.LexiangCredentialDTO;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.service.LexiangService;
import com.huzhijian.nexusagentweb.vo.LexiangCredentialVO;
import com.huzhijian.nexusagentweb.vo.LexiangSpaceVO;
import com.huzhijian.nexusagentweb.vo.LexiangTeamVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 乐享知识库接入。
 * <p>
 * <b>范围只有「接入 + 检索」</b>：保存凭证、列出团队/知识库供用户选择、连通性测试。
 * <b>不提供文件上传接口</b> —— 用户的管理与维护仍在乐享侧完成。
 * <p>
 * AppSecret 全程不落明文、不出接口：读接口只回 {@code secretSaved} 布尔值。
 *
 * @author 胡志坚
 * @version 1.0
 */
@RestController
@RequestMapping("/api/lexiang")
@RequiredArgsConstructor
@Tag(name = "乐享知识库", description = "接入用户的腾讯乐享知识库（只读检索，不支持上传）")
public class LexiangController {

    private final LexiangService lexiangService;

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录");
        }
        return userId;
    }

    @Operation(summary = "保存乐享接入凭证（AppKey / AppSecret / 成员账号）",
            description = """
                    AppSecret 会加密后存储，**接口响应永不回显明文**。
                    `staffId` 即乐享的 `x-staff-id`，它决定检索结果里能看到哪些内容：
                    填自己的成员账号可检索私有知识，填 `system-bot` 只能检索全公司公开知识。
                    重复保存即覆盖（一个用户只保留一份配置）。
                    """)
    @PostMapping("/credential")
    public Result saveCredential(@RequestBody @Valid LexiangCredentialDTO dto) {
        Long userId = requireUserId();
        lexiangService.save(userId, dto.appKey(), dto.appSecret(), dto.staffId(),
                dto.defaultTeamId(), dto.defaultSpaceId());
        return Result.ok();
    }

    @Operation(summary = "读取当前用户的乐享凭证（不回显 AppSecret 明文）")
    @GetMapping("/credential")
    public Result getCredential() {
        Long userId = requireUserId();
        LexiangCredential credential = lexiangService.getByUserId(userId);
        if (credential == null) {
            return Result.ok(null);
        }
        return Result.ok(LexiangCredentialVO.builder()
                .appKey(credential.getAppKey())
                // 只回布尔值：前端据此显示"已配置"，但拿不到原密钥
                .secretSaved(true)
                .staffId(credential.getStaffId())
                .defaultTeamId(credential.getDefaultTeamId())
                .defaultSpaceId(credential.getDefaultSpaceId())
                .build());
    }

    @Operation(summary = "列出可访问的团队（选择知识库的前置步骤）")
    @GetMapping("/teams")
    public Result listTeams() {
        List<LexiangTeamVO> teams = lexiangService.listTeams(requireUserId());
        return Result.ok(teams);
    }

    @Operation(summary = "列出某团队下的知识库",
            description = "乐享的层级是 team → space → entry，所以**必须先选团队**才能列知识库。")
    @GetMapping("/spaces")
    public Result listSpaces(@RequestParam("teamId") String teamId) {
        List<LexiangSpaceVO> spaces = lexiangService.listSpaces(requireUserId(), teamId);
        return Result.ok(spaces);
    }

    @Operation(summary = "设为默认检索知识库",
            description = "用户在乐享里换了主用的知识库后调它。只改检索范围，不触碰已存的 AppSecret。")
    @PutMapping("/space")
    public Result setDefaultSpace(@RequestParam("spaceId") String spaceId,
                                  @RequestParam(value = "teamId", required = false) String teamId) {
        lexiangService.updateDefaultSpace(requireUserId(), spaceId, teamId);
        return Result.ok();
    }

    @Operation(summary = "连通性测试（真实检索一次）",
            description = """
                    会用当前凭证真实调用乐享的 AI 检索，因此能同时验证：
                    AppSecret 是否正确、AppKey 授权范围是否包含目标知识库、`x-staff-id` 是否存在。
                    """)
    @PostMapping("/test")
    public Result testConnection(@RequestParam(value = "staffId", required = false) String staffId,
                                 @RequestParam(value = "spaceId", required = false) String spaceId) {
        int hits = lexiangService.testConnection(requireUserId(), staffId, spaceId);
        return Result.ok(hits);
    }
}
