package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 技能列表项（技能库页面用）。
 * <p>
 * 官方技能（来自部署目录）与用户技能（来自 DB）统一成这个结构，
 * 靠 {@code source} 区分；官方技能的 id 为 null、authorName 为「官方」。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Data
@AllArgsConstructor
public class SkillVO {

    /** DB 技能才有；官方技能为 null */
    private Long id;

    /** 技能名，模型按它激活 */
    private String name;

    /** 触发条件说明 */
    private String description;

    /** {@code UPLOAD} / {@code AI_GENERATED} / {@code BUILTIN} */
    private String source;

    /** {@code PRIVATE} / {@code PUBLIC}；官方技能固定为 {@code BUILTIN} 语义，前端展示为「官方」 */
    private String visibility;

    /** 作者展示名；官方技能为「官方」 */
    private String authorName;

    /** 是否是当前登录用户自己的 */
    private Boolean owned;

    /** 被使用次数，热门排序用 */
    private Integer useCount;

    private java.sql.Timestamp updatedAt;

    /** 附属资源列表（不含内容，只有路径，避免列表接口把全文吐出来） */
    private List<String> resourcePaths;
}