package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
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

//    从MCP服务供应商中拿到MCP信息
    @GetMapping("/service")
    public Result getMcpServerByService() {
        List<McpServerItemVO> information = mcpInformationService.getMcpInformationByService();
        return Result.ok(information);
    }
//    从数据库中拿到MCP信息
    @GetMapping
    public Result getMcpServer() {
        List<McpServerItemVO> information = mcpInformationService.getMcpInformation();
        return Result.ok(information);
    }
    @DeleteMapping("/{id}")
    public Result deleteMcpServerByService(@PathVariable Long id) {
        mcpInformationService.removeMCP(id);
        return Result.ok();
    }
    @PutMapping
    public Result updateMcpServer(@RequestBody McpServerItemDTO MCPs) {
        mcpInformationService.updateMCPById(MCPs);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result getMcpServerDetailById(@PathVariable Long id) {
        McpDetailVO detail=mcpInformationService.getDetailById(id);
        return Result.ok(detail);
    }


    @PostMapping
    public Result addMcpServer(@RequestBody List<McpServerItemDTO> MCPs) {
        mcpInformationService.saveMcp(MCPs);
        return Result.ok();
    }

}
