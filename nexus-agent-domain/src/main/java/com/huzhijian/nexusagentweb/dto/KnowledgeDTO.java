package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/28
 * 说明:
 */
public record KnowledgeDTO(@NotBlank String name,
                           String describe, Boolean isPublic,
                           String languageCode) {
}
