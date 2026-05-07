package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.McpInformation;

import java.util.List;

/**
* @author windows
* @description 针对表【mcp_information(MCP配置信息)】的数据库操作Mapper
* @createDate 2026-04-21 20:54:09
* @Entity com.huzhijian.nexusagentweb.domain.McpInformation
*/
public interface McpInformationMapper extends BaseMapper<McpInformation> {

    void saveBatch(List<McpInformation> mcpInformationList);
}




