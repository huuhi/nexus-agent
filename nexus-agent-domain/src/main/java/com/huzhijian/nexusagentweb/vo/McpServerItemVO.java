package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * MCP服务器信息视图对象
 */
@Data
@Builder
@AllArgsConstructor
public class McpServerItemVO {
    private String strId;
    private String id;
    private String url;
    private String description;
    private String name;
    private String logoUrl;
    private String type;
    private Boolean available;
}