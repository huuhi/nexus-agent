package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 用户技能：用户上传或用模型生成的 Skill。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 * 说明: 对应 minmax / Claude Code 的「社区技能」。
 * <p>
 * <b>为什么存 DB 而不是解压到 skills/ 目录</b>：{@code skills/README.md} 明确写了
 * 「不要把用户可写的上传目录配成 root-dir」。上传 zip 解压落盘要额外解决路径穿越、
 * zip 炸弹、清理时机、多用户隔离四件事；而 {@code Skills.from(...)} 接受任意
 * {@code Skill} 实现，正文与资源都可以放内存里装配 —— 于是落盘这一步被整个消掉。
 * <p>
 * <b>与官方技能的关系</b>：官方技能来自部署目录（{@code nexus.agent.skill.root-dir}），
 * 不进本表。它们在 {@code SkillLoader} 里被合并成同一份可用清单。
 */
@TableName(value = "user_skill")
@Data
@Builder
public class UserSkill {

    @TableId
    private Long id;

    /** 作者的用户 id */
    private Long userId;

    /** 技能名，全局唯一（模型 activate_skill 按名匹配，重名会让路由不确定） */
    private String name;

    /** 给模型看的触发条件。必须写「何时用」而不是「是什么」 */
    private String description;

    /** SKILL.md 中 frontmatter 之后的正文 */
    private String content;

    /**
     * 解析出的原始 YAML frontmatter，<b>存 jsonb 列的 JSON 文本</b>。
     * <p>
     * ⚠️ Java 侧必须是 String 而不是 Map/Object：声明成 Object 时读出来是
     * PGobject，序列化成 JSON 会变成 {"type":"jsonb","value":"{...}"} 这种嵌套壳子
     * （McpInformation.header 与 UserConfig.llmApiToken 都踩过）。
     * <p>
     * 保留原始头是为了将来支持 allowed-tools / license / version 等官方规范字段。
     */
    private String frontmatter;

    /**
     * 附属资源，jsonb 列的 JSON 文本，形如
     * {@code [{"path":"notes.md","content":"..."}]}。
     * <p>
     * 对应 {@code read_resource(skillName, relativePath)} 能读到的那些文件。
     * {@code scripts/} 下的内容**不入库**（langchain4j 刻意读不到它）。
     */
    private String resources;

    /** {@code PRIVATE}（仅作者可见）/ {@code PUBLIC}（社区共享） */
    private String visibility;

    /** {@code UPLOAD} / {@code AI_GENERATED} / {@code BUILTIN}（后者仅列表展示用） */
    private String source;

    /** false 表示下架：不进入对话上下文，但记录保留 */
    private Boolean enabled;

    /** 被使用的次数，列表页热门排序依据 */
    private Integer useCount;

    private Timestamp createdAt;

    private Timestamp updatedAt;

    /** 非空化，避免前端/DB 侧遇到 null */
    public boolean isEnabledFlag() {
        return enabled != null && enabled;
    }
}