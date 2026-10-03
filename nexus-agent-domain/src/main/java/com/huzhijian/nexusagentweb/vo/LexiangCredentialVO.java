package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 乐享接入凭证的对外视图。
 * <p>
 * <b>刻意不含 appSecret</b> —— 即使用户自己填的，响应里也一律不回显明文。
 * 只回一个 {@code secretSaved} 布尔值让前端知道"已配置"。
 */
@Data
@Builder
public class LexiangCredentialVO {

    /** AppKey（明文标识符，可回显） */
    private String appKey;

    /** 是否已保存过 AppSecret（只回布尔值，不回密文） */
    private Boolean secretSaved;

    /** 发起检索的成员账号（x-staff-id） */
    private String staffId;

    private String defaultTeamId;

    private String defaultSpaceId;
}
