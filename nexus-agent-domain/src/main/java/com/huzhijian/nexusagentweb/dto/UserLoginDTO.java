package com.huzhijian.nexusagentweb.dto;

import com.huzhijian.nexusagentweb.em.LoginType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明:
 */
public record UserLoginDTO(@NotBlank(message = "邮箱不能为空！") @Email(message = "邮箱格式错误！") String email, String password, String code, @NotNull(message = "登录类型错误！") LoginType type) {
}
