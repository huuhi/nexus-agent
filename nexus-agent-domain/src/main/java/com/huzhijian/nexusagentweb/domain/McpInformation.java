package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * MCP配置信息
 * @TableName mcp_information
 */
@TableName(value ="mcp_information")
@Data
public class McpInformation {
    /**
     * 
     */
    @TableId
    private Long id;

    /**
     * 
     */
    private String name;

    /**
     * 
     */
    private String url;

    /**
     * 
     */
    private String description;

    private String logoUrl;

    /**
     * 
     */
    private Object header;

    /**
     * 
     */
    private Long userId;

    /**
     * MCP类型
     */
    private String type;
}