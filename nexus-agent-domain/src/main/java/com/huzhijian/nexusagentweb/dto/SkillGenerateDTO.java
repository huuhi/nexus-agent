package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 生成技能的请求。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Data
public class SkillGenerateDTO {

    /**
     * 用户想做的事（自然语言）。
     * <p>
     * 这是唯一必填项 —— 模型据此写出 {@code name} / {@code description} / 正文。
     */
    @NotBlank(message = "请描述你想要什么样的技能！")
    @Size(max = 2000, message = "描述过长（最多 2000 字）！")
    private String requirement;

    /**
     * 可选：期望的技能名。
     * <p>
     * 留空由模型自己想。填了的话模型会尽量采用，且服务端会强制走同一套命名校验，
     * 所以填「My Skill」这种带空格大写的会被拒 —— 这是有意的：
     * 技能名要当目录名用，也会被拼进 {@code activate_skill(skillName)} 的提示词。
     */
    @Size(max = 64, message = "技能名过长（最多 64 字符）！")
    private String name;

    /**
     * 可选：补充约束。例如「只用中文」「不要依赖沙盒」「步骤必须可判定」。
     */
    @Size(max = 1000, message = "补充说明过长（最多 1000 字）！")
    private String extra;

    /**
     * 可选：模型要附带的参考资源。
     * <p>
     * 生成结果里如果带上模板/参考资料，模型只能凭空编 —— 让用户把自己的文件传进来，
     * 生成的技能才真正可用（对应 minmax 那种「带模板的技能」）。
     */
    private List<ResourceDTO> referenceResources;

    /**
     * 参考资源。
     */
    @Data
    public static class ResourceDTO {
        @NotBlank(message = "资源路径不能为空！")
        private String path;

        @NotBlank(message = "资源内容不能为空！")
        private String content;
    }
}