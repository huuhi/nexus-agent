package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/27
 * 说明:
 */
public record UserPasswordDTO(@NotBlank @Email String email,@NotBlank String code,@NotBlank String password) {
}
