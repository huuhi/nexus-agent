package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
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
