package com.huzhijian.nexusagentweb.em;

/**
 * 技能来源。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
public enum SkillSource {
    /** 用户上传 .zip / .md */
    UPLOAD,
    /** 由模型生成（草稿经用户确认后保存） */
    AI_GENERATED,
    /**
     * 官方内置。
     * <p>
     * <b>官方技能实际不存本表</b>，它们来自部署目录 {@code nexus.agent.skill.root-dir}。
     * 这个值只用于列表接口把两类技能统一成同一个 VO 时标注来源。
     */
    BUILTIN;

    public static boolean isValid(String value) {
        for (SkillSource v : values()) {
            if (v.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}