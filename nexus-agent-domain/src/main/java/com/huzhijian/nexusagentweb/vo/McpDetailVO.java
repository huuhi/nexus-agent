package com.huzhijian.nexusagentweb.vo;


import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/8
 * 说明:
 */
@Data
@AllArgsConstructor
public class McpDetailVO {
    /**
     *
     */
    private Long id;

    //    MCP 服务 唯一标识
    private String strId;

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
     * MCP类型
     */
    private String type;

    private Boolean available;
}
