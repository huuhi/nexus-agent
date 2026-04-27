package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
public record KnowledgeDTO(@NotEmpty(message = "文件ID不能为空！") List<Long> fileIds, @NotNull(message = "知识库ID不能为空！") Integer knowledgeId) {
}
