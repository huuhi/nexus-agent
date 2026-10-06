package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 当前用户的资料（2026-10-06 新增，配套 {@code GET /api/user/profile}）。
 *
 * <p><b>为什么要有它</b>：原先前端拿用户信息只能<b>解码 JWT</b>，而那有三个问题：
 * <ol>
 *   <li><b>没有 email</b> —— 设置页要显示邮箱，做不到；</li>
 *   <li><b>改了不会同步</b> —— JWT 里那份 {@code user_name}/{@code image_url} 是
 *       <b>登录那一刻</b>的快照，而 token 有效期 7 天。用户改了昵称，
 *       页面上 7 天内还是旧的；</li>
 *   <li>JWT 是<b>签名过的凭据</b>，拿它当数据源，前端一旦解析逻辑有误会显示错的用户。</li>
 * </ol>
 * 所以接口返回<b>数据库当前值</b>，是唯一权威来源。
 *
 * <p>⚠️ <b>不要改 email</b>：它是登录凭据（唯一键），改它等于换账号。
 * <b>不要改 role</b>：那是运营在库里授予的档位，不该由用户自己给自己升 VIP。
 *
 * @author 胡志坚
 */
@Data
@Builder
public class UserProfileVO {

    /** 用户 id（雪花 ID，序列化成<b>字符串</b>） */
    private Long id;

    /** 邮箱 —— 登录凭据，只读 */
    private String email;

    /** 昵称 —— 可改 */
    private String username;

    /**
     * 头像 URL（OSS）。
     * <p>
     * 注册时有一个默认头像（见 {@code UserServiceImpl#register}）。
     * 换头像：先调 {@code POST /api/file/image} 上传拿到 URL，再通过
     * {@code PUT /api/user/profile} 把这个 URL 写进来。
     * <p>
     * ⚠️ 老数据可能是 {@code null}，前端要能兜住「无头像」这个状态。
     */
    private String avatarImg;

    /** 角色档位 {@code NORMAL} / {@code TEST} / {@code VIP} —— 只读 */
    private String role;

    /** 注册时间 —— 只读 */
    private Date registerTime;
}