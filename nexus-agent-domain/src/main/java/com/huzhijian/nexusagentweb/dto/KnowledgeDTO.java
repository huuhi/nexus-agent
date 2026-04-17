package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
@Data
@AllArgsConstructor
public class KnowledgeDTO {
    @NotEmpty
    List<Long> fileIds;
    @NotNull
    Integer knowledgeId;
}
