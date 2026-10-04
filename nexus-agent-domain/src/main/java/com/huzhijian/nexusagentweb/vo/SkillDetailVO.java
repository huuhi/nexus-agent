package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 技能详情（含正文与资源全文）。
 * <p>
 * 与 {@link SkillVO} 分开是因为列表页不该把每个技能的正文都吐出来 ——
 * 技能正文会全部进系统提示词，量很大。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Data
@AllArgsConstructor
public class SkillDetailVO {

    private Long id;
    private String name;
    private String description;

    /** SKILL.md 正文（不含 frontmatter） */
    private String content;

    /** 原始 frontmatter 的 JSON 文本 */
    private String frontmatter;

    private String source;
    private String visibility;
    private String authorName;
    private Boolean owned;
    private Integer useCount;
    private Boolean enabled;

    /** 资源全文列表，供前端预览与编辑 */
    private List<SkillResourceVO> resources;

    private java.sql.Timestamp createdAt;
    private java.sql.Timestamp updatedAt;

    /**
     * 附属资源。
     *
     * @author 胡志坚
     */
    @Data
    @AllArgsConstructor
    public static class SkillResourceVO {
        /** 相对路径，如 {@code notes.md} */
        private String path;
        private String content;
    }
}