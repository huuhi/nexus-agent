package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import dev.langchain4j.mcp.McpToolProvider;

import java.util.List;

/**
* @author windows
* @description 针对表【mcp_information(MCP配置信息)】的数据库操作Service
* @createDate 2026-04-21 20:54:09
*/
public interface McpInformationService extends IService<McpInformation> {

    McpToolProvider getMcp(List<Long> mcPs,Long userId);

    List<McpServerItemVO> getMcpInformation();

    void saveMcp(List<McpServerItemDTO> mcPs);
}
