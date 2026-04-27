package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.utils.EmailUtils;
import com.huzhijian.nexusagentweb.vo.Result;
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
@RequestMapping("/common")
public class CommonController {
//    发送邮箱
    private final EmailUtils emailUtils;


    public CommonController(EmailUtils emailUtils) {
        this.emailUtils = emailUtils;
    }
    @PostMapping("/email")
    public Result sendEmail(@RequestParam @Valid @NotBlank String email){
        emailUtils.sendEmail(email,"验证码");
        return Result.ok("验证码发送成功！");
    }

}
