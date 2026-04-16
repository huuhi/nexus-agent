package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.SkillMcpInformation;
import com.baomidou.mybatisplus.extension.service.IService;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.skills.Skills;

import java.util.List;

/**
* @author windows
* @description 针对表【skill_mcp_information】的数据库操作Service
* @createDate 2026-04-16 20:02:30
*/
public interface SkillMcpInformationService extends IService<SkillMcpInformation> {

    Skills getSkills(List<String> skills);

    McpToolProvider getMcp(List<String> mcPs);
}
