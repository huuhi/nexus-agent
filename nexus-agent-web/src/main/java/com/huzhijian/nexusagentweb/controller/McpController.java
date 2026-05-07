package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import com.huzhijian.nexusagentweb.vo.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/6
 * 说明:
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private final McpInformationService mcpInformationService;

    public McpController(McpInformationService mcpInformationService) {
        this.mcpInformationService = mcpInformationService;
    }

    @GetMapping
    public Result getMcpServer() {
        List<McpServerItemVO> information = mcpInformationService.getMcpInformation();
        return Result.ok(information);
    }

    @PostMapping
    public Result addMcpServer(@RequestBody List<McpServerItemDTO> MCPs) {
        mcpInformationService.saveMcp(MCPs);
        return Result.ok();
    }
}
