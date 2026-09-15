package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/12
 * 说明:
 */
@Data
@AllArgsConstructor
public class MemorySearchResult {
    private Long id;
    private String content;
    private String category;
    private Float score;
}
