package com.huzhijian.nexusagentweb.dto;

import lombok.Builder;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/12
 * 说明:
 */
@Builder
public record SearchMemoryRequest(Long userId, float[] embedding, int maxResult, float minScore) {
}
