package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.UserSkill;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户技能 Mapper。
 * <p>
 * 纯 CRUD，不写 XML —— {@code resources} / {@code frontmatter} 两个 jsonb 列
 * 在 Java 侧已是 String，MyBatis-Plus 自动生成的 SQL 会把它们当普通 varchar 处理，
 * **而 PG 对 jsonb 列接受 varchar 字面量**（隐式转换），所以这里不需要 {@code ::jsonb}。
 * 手写 XML 时才必须显式加（见 McpInformationMapper.xml）。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@Mapper
public interface UserSkillMapper extends BaseMapper<UserSkill> {
}