package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明:
 */
public record UserRegisterDTO(@Email(message = "邮箱格式错误！") @NotBlank(message = "邮箱不能为空") String email, String username, @NotBlank(message = "验证码不能为空！") String code) {
}
