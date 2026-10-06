package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改当前用户资料（2026-10-06 新增，配套 {@code PUT /api/user/profile}）。
 *
 * <p><b>两个字段都是可选的</b>：只传 {@code username} 就只改昵称，只传 {@code avatarImg}
 * 就只改头像；都不传则什么都不改（不报错 —— 前端「保存」按钮在没改动时也可点）。
 *
 * <p>⚠️ <b>不能改 email</b>：它是登录凭据（库里有唯一约束），改它等于换账号。
 * 要换邮箱得另做「验证新邮箱 + 重新绑定」的流程，不能靠这个接口。
 * <p>⚠️ <b>不能改 role</b>：那是运营在库里授予的档位，不该由用户自己升 VIP。
 *
 * @param username  新昵称；{@code null} 表示不改。长度上限 64（库里 varchar(255)，
 *                  这里收得更紧是给 UI 留余量，超长昵称在气泡里会换行很难看）
 * @param avatarImg 新头像 URL；{@code null} 表示不改。
 *                  <b>必须是本项目 OSS 的域名</b> —— 服务端会校验，
 *                  否则用户可以把任意外链（甚至带追踪像素的图片）存进库，
 *                  每次页面渲染都等于给第三方递一次访问。
 *                  换头像的正确顺序：先 {@code POST /api/file/image} 上传，再把返回的 URL 传这里
 * @author 胡志坚
 */
public record UpdateProfileDTO(
        @Size(max = 64, message = "昵称最长 64 个字符") String username,
        String avatarImg) {
}