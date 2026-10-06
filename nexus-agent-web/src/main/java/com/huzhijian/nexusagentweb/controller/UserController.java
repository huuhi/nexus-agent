package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.dto.UpdateProfileDTO;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
import com.huzhijian.nexusagentweb.vo.UserProfileVO;
import com.huzhijian.nexusagentweb.vo.Result;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明: 用户相关控制器，包括登录/配置等接口
 */

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户", description = "登录/注册/密码、LLM 与 MCP 配置、长期记忆、token 配额")
public class UserController {
    private final UserService userService;
    private final UserConfigService userConfigService;
    private final UserMemoryService userMemoryService;
    private final QuotaService quotaService;

    // 下面三个是免鉴权接口：@SecurityRequirements 留空 = 覆盖掉全局的 token 要求，
    // 否则 Swagger UI 会给它们也加一把锁，试接口的人会以为必须登录。

    // ==================================================================
    //  用户资料（2026-10-06 新增）：头像上传 + 设置页表单化
    //
    //  🔴 为什么不能继续用 JWT 里那份 user_name / image_url：
    //  ① JWT 里**没有 email**，设置页要显示邮箱做不到；
    //  ② 那份是**登录那一刻的快照**，而 token 有效期 7 天 —— 用户改了昵称，
    //     页面上 7 天内还是旧的；
    //  ③ JWT 是签名过的**凭据**，拿它当数据源，前端解析逻辑一错就显示错用户。
    //  所以这里返回数据库当前值，作为唯一权威来源。
    // ==================================================================

    @Operation(summary = "取当前用户资料",
            description = "返回 id / email / username / avatarImg / role / registerTime。"
                    + "id 是雪花 ID，序列化成**字符串**。avatarImg 可能为 null（老数据），前端要能兜住「无头像」。")
    @GetMapping("/profile")
    public Result getProfile() {
        return Result.ok(userService.getProfile(UserContextHolder.getUserId()));
    }

    @Operation(summary = "修改当前用户资料",
            description = "只允许改 username 与 avatarImg；都不传则什么都不做且不报错。"
                    + "avatarImg 必须是本项目 OSS 的 URL（服务端校验域名）—— 换头像请先 POST /api/file/image 上传，"
                    + "再把返回的 URL 传到这里。email 与 role 只读。昵称最长 64 字符。")
    @PutMapping("/profile")
    public Result updateProfile(@RequestBody @Valid UpdateProfileDTO dto) {
        userService.updateProfile(UserContextHolder.getUserId(), dto.username(), dto.avatarImg());
        return Result.ok("资料已更新", userService.getProfile(UserContextHolder.getUserId()));
    }

    @Operation(summary = "登录", description = "返回 JWT，后续请求放进请求头 `token`。")
    @SecurityRequirements
    @PostMapping("/login")
    public Result login(@RequestBody @Valid UserLoginDTO loginDTO){
        String token= userService.login(loginDTO);
        return Result.ok(token);
    }

    @Operation(summary = "注册")
    @SecurityRequirements
    @PostMapping("/register")
    public Result register(@RequestBody @Valid UserRegisterDTO registerDTO){
        String token= userService.register(registerDTO);
        return Result.ok(token);
    }
//    忘记密码/设置密码
    @Operation(summary = "设置 / 重置密码", description = "走邮箱验证码；免鉴权（否则忘密码的人永远进不来）。")
    @SecurityRequirements
    @PutMapping("/password")
    public Result setPassword(@RequestBody UserPasswordDTO  passwordDTO){
        userService.updateOrSetPassword(passwordDTO);
        return Result.okWithMsg("设置成功!");
    }
//    添加/修改 配置
    @PostMapping("/api-config")
    public Result setOrUpdateAPIConfig(@RequestBody APIConfig config){
        userConfigService.saveOrUpdateAPIConfig(config);
        return Result.okWithMsg("设置成功！");
    }
    @PostMapping("/mcp-config")
    public Result setMcpToken(@RequestParam String token){
        userConfigService.saveOrUpdateMcpToken(token);
        return Result.okWithMsg("设置成功！");
    }
    @GetMapping("/api-config")
    public Result getAPIConfig(){
        List<APIConfig> configs=userConfigService.getApiConfig();
        return Result.ok(configs);
    }
    @GetMapping("/mcp-config")
    public Result getMCPConfig(){
        String config=userConfigService.getMCPConfig();
        return Result.ok(config);
    }
    @GetMapping("/user-memory")
    public Result getUserMemory(@RequestParam(required = false) String key){
        List<UserMemoryVO> list=userMemoryService.getMemory(key);
        return Result.ok(list);
    }
//    删除
    @DeleteMapping("/user-memory/{id}")
    public Result deleteUserMemory(@PathVariable Long id){
        userMemoryService.deleteById(id);
        return Result.ok();
    }

    /**
     * 查询当前登录用户的 token 配额与用量（P2-8 遗留）。
     * <p>
     * 用户身份取 {@link UserContextHolder}（由 {@code LoginCheckInterceptor} 写入），
     * **不接受任何入参** —— 否则就成了「传个 userId 就能看别人用量」的越权口子。
     * <p>
     * 该接口不抛业务异常：查询失败（典型是没执行 {@code docs/sql/003} 或 {@code 007}）
     * 时返回 {@code degraded=true} 的占位对象，前端据此提示"配额信息暂不可用"。
     */
    @Operation(summary = "查询当前用户的 token 配额与用量", description = """
            返回 `QuotaVO`。**查询失败不抛异常**，而是返回 `degraded=true` 的占位对象 ——
            典型原因是 `docs/sql/003` / `007` 没执行（缺列）。
            前端见到 `degraded=true` 应提示"配额信息暂不可用"，**不要**把 null 显示成 0。
            """)
    @GetMapping("/quota")
    public Result getQuota(){
        Long userId = UserContextHolder.getUserId();
        QuotaVO quota = quotaService.getQuota(userId);
        return Result.ok(quota);
    }

}
