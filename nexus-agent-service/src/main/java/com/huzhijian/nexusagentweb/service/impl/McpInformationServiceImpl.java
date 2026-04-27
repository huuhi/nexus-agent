package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.mapper.McpInformationMapper;
import dev.langchain4j.mcp.McpToolProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author windows
* @description 针对表【mcp_information(MCP配置信息)】的数据库操作Service实现
* @createDate 2026-04-21 20:54:09
*/
@Service
public class McpInformationServiceImpl extends ServiceImpl<McpInformationMapper, McpInformation>
    implements McpInformationService{

    @Override
    public McpToolProvider getMcp(List<String> mcPs) {
        return null;
    }
}




