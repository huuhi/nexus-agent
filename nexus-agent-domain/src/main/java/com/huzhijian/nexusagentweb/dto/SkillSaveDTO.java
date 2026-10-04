package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 保存技能的请求（上传解析后或 AI 生成后，用户确认再落库）。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Data
public class SkillSaveDTO {

    @NotBlank(message = "技能名不能为空！")
    @Size(max = 64, message = "技能名过长（最多 64 字符）！")
    private String name;

    @NotBlank(message = "技能说明不能为空！")
    @Size(max = 500, message = "技能说明过长（最多 500 字）！")
    private String description;

    @NotBlank(message = "技能正文不能为空！")
    private String content;

    /** {@code PRIVATE}（默认）/ {@code PUBLIC}`（社区共享） */
    private String visibility;

    /** {@code UPLOAD} / {@code AI_GENERATED}；不传按 UPLOAD 处理 */
    private String source;

    /** 附属资源 */
    private List<ResourceDTO> resources;

    /**
     * 附属资源。
     */
    @Data
    public static class ResourceDTO {
        @NotBlank(message = "资源路径不能为空！")
        @Size(max = 200, message = "资源路径过长！")
        private String path;

        private String content;
    }
}