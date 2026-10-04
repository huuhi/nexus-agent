package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.dto.SkillGenerateDTO;
import com.huzhijian.nexusagentweb.dto.SkillSaveDTO;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser;
import com.huzhijian.nexusagentweb.vo.SkillDetailVO;
import com.huzhijian.nexusagentweb.vo.SkillVO;
import dev.langchain4j.skills.Skill;

import java.util.List;

/**
 * 用户技能服务。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
public interface UserSkillService {

    /**
     * 技能库列表：官方（部署目录）+ 社区公开 + 自己私有的。
     *
     * @param userId  当前登录用户
     * @param keyword 关键词，匹配技能名或说明；为空不过滤
     * @param source  来源过滤；为空表示不限
     * @return 列表
     */
    List<SkillVO> list(Long userId, String keyword, String source);

    /**
     * 技能详情（含正文与资源全文）。
     *
     * @param userId 当前登录用户
     * @param name   技能名（官方技能用它，与用户技能同一个命名空间）
     * @return 详情
     */
    SkillDetailVO detail(Long userId, String name);

    /**
     * 解析上传的技能包，<b>不落库</b>，只返回解析结果供用户确认。
     * <p>
     * 为什么要分两步：解析可能失败（格式不对、缺 frontmatter），也让用户有机会
     * 先看看模型/解压到底读出了什么，再决定要不要存。
     *
     * @param userId        当前登录用户
     * @param content       文件字节
     * @param originalName  原始文件名
     * @return 解析出的草稿
     */
    SkillPackageParser.SkillDraft parseUpload(Long userId, byte[] content, String originalName);

    /**
     * 用模型生成技能草稿，<b>不落库</b>。
     *
     * @param userId 当前登录用户
     * @param dto    生成请求
     * @return 草稿
     */
    SkillPackageParser.SkillDraft generate(Long userId, SkillGenerateDTO dto);

    /**
     * 保存技能（上传或 AI 生成后，用户确认再存）。
     *
     * @param userId 当前登录用户
     * @param dto    保存请求
     * @return 存好的技能详情
     */
    SkillDetailVO save(Long userId, SkillSaveDTO dto);

    /**
     * 修改自己创建的技能。
     *
     * @param userId 当前登录用户
     * @param name   技能名（定位用）
     * @param dto    修改内容
     * @return 更新后的详情
     */
    SkillDetailVO update(Long userId, String name, SkillSaveDTO dto);

    /**
     * 删除自己创建的技能。
     *
     * @param userId 当前登录用户
     * @param name   技能名
     */
    void delete(Long userId, String name);

    /**
     * 上架 / 下架（切换是否进入对话上下文）。
     *
     * @param userId  当前登录用户
     * @param name    技能名
     * @param enabled 目标状态
     */
    void setEnabled(Long userId, String name, boolean enabled);

    /**
     * 供对话链路使用：该用户当前可用的用户技能（已转成内存 {@link Skill}）。
     * <p>
     * 官方技能不在这里 —— 它们由 {@code SkillLoader} 从部署目录扫描，
     * 由 {@code SkillLoader} 负责合并。
     *
     * @param userId 当前登录用户
     * @return 可用的用户技能列表
     */
    List<Skill> loadForChat(Long userId);
}