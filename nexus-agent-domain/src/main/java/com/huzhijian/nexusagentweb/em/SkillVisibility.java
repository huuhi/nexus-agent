package com.huzhijian.nexusagentweb.em;

/**
 * 技能可见性。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
public enum SkillVisibility {
    /** 只有作者自己可见（默认） */
    PRIVATE,
    /** 所有登录用户可见，即 minmax 界面上的「社区 / 用户贡献」 */
    PUBLIC;

    public static boolean isValid(String value) {
        for (SkillVisibility v : values()) {
            if (v.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}