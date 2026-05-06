package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.vo.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明: 用户相关控制器，包括登录/配置等接口
 */

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;
    private final UserConfigService userConfigService;
    public UserController(UserService userService, UserConfigService userConfigService) {
        this.userService = userService;
        this.userConfigService = userConfigService;
    }

    @PostMapping("/login")
    public Result login(@RequestBody @Valid UserLoginDTO loginDTO){
        String token= userService.login(loginDTO);
        return Result.ok(token);
    }
    @PostMapping("/register")
    public Result register(@RequestBody @Valid UserRegisterDTO registerDTO){
        String token= userService.register(registerDTO);
        return Result.ok(token);
    }
//    忘记密码/设置密码
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

}
