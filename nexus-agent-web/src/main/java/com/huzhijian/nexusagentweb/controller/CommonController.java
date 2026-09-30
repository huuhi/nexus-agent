package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.utils.EmailUtils;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明:
 */
@RestController
@RequestMapping("/api/common")
@Tag(name = "通用", description = "邮箱验证码等与登录态无关的能力")
public class CommonController {
//    发送邮箱
    private final EmailUtils emailUtils;


    public CommonController(EmailUtils emailUtils) {
        this.emailUtils = emailUtils;
    }
    @Operation(summary = "发送邮箱验证码", description = "注册 / 重置密码用；免鉴权。")
    @SecurityRequirements
    @PostMapping("/email")
    public Result sendEmail(@RequestParam @Valid @NotBlank String email){
        emailUtils.sendEmail(email,"验证码");
        return Result.ok("验证码发送成功！");
    }

}
