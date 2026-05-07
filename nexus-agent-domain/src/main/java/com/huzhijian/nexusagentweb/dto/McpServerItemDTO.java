package com.huzhijian.nexusagentweb.dto;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/7
 * 说明:
 */
public record McpServerItemDTO(String url,
                               String description,
                               String name,
                               String logoUrl,
                               String type, Map<String,Object> header){}
