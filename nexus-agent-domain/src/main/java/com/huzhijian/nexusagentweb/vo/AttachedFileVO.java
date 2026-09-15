package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/20
 * 说明:
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AttachedFileVO {
    private String id;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String extension;
}
