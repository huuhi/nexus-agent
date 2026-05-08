package com.huzhijian.nexusagentweb.domain;

import jdk.jfr.Description;
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
public class UserLongMemory {
    @Description("记忆ID")
    private String id;
    @Description("记忆内容")
    private String content;
    @Description("记忆的分类")
    private String category;
}
