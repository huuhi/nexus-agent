package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话重命名入参（P3-1 补）。
 * <p>
 * 为什么单独建一个 DTO 而不是用 {@code @RequestParam}：
 * 标题是用户自由输入，长度必须卡住 —— 数据库列是 {@code varchar(255)}，
 * 但**标题是给人看的**，超过 100 字符的标题没有任何可读性，所以在入口就拦掉，
 * 而不是等PostgreSQL抛 {@code value too long}（那会变成 500）。
 * <p>
 * 校验失败由 {@code GlobalExceptionHandler#handleMethodArgumentNotValid}
 * 转成 HTTP 200 + {@code Result{code=1, msg=...}}，前端按 code 判断即可。
 *
 * @param title 新标题，不能为空，最长 100 字符（首尾空白会被 Service 层去掉）
 */
public record RenameSessionDTO(
        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题不能超过 100 个字符")
        String title) {
}
