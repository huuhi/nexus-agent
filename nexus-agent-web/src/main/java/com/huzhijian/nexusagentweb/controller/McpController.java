package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.vo.McpDetailVO;
import com.huzhijian.nexusagentweb.vo.McpServerItemVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/6
 * 说明:
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP", description = "MCP 服务配置（增删改查）与服务商预置列表")
public class McpController {

    private final McpInformationService mcpInformationService;

    public McpController(McpInformationService mcpInformationService) {
        this.mcpInformationService = mcpInformationService;
    }

//    从MCP服务供应商中拿到MCP信息
    @Operation(summary = "MCP 服务商预置列表（可直接添加的模板）")
    @GetMapping("/service")
    public Result getMcpServerByService() {
        List<McpServerItemVO> information = mcpInformationService.getMcpInformationByService();
        return Result.ok(information);
    }
//    从数据库中拿到MCP信息
    @Operation(summary = "当前用户已配置的 MCP 服务")
    @GetMapping
    public Result getMcpServer() {
        List<McpServerItemVO> information = mcpInformationService.getMcpInformation();
        return Result.ok(information);
    }
    @Operation(summary = "删除 MCP 服务配置")
    @DeleteMapping("/{id}")
    public Result deleteMcpServerByService(@PathVariable Long id) {
        mcpInformationService.removeMCP(id);
        return Result.ok();
    }
    @Operation(summary = "修改 MCP 服务配置")
    @PutMapping
    public Result updateMcpServer(@RequestBody McpServerItemDTO MCPs) {
        mcpInformationService.updateMCPById(MCPs);
        return Result.ok();
    }

    @Operation(summary = "MCP 服务配置详情")
    @GetMapping("/{id}")
    public Result getMcpServerDetailById(@PathVariable Long id) {
        McpDetailVO detail=mcpInformationService.getDetailById(id);
        return Result.ok(detail);
    }


    @Operation(summary = "批量新增 MCP 服务配置")
    @PostMapping
    public Result addMcpServer(@RequestBody List<McpServerItemDTO> MCPs) {
        mcpInformationService.saveMcp(MCPs);
        return Result.ok();
    }

}
