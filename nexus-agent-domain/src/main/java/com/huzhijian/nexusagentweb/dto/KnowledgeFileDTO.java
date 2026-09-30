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
/**
 * 上传文件到知识库的请求体。
 *
 * <p><b>⚠️ configId / model 已废弃（P2-13）</b>：向量模型**统一使用系统默认模型**（入库与检索共用），
 * 前端传了也不会生效，且<b>不再强制必填</b> —— 旧版本要求必填，导致「没配过 API Key 的用户」
 * 连校验都过不去，根本建不了知识库。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
public record KnowledgeFileDTO(@NotEmpty(message = "文件ID不能为空！") List<Long> fileIds,
                               @NotNull(message = "知识库ID不能为空！") Integer knowledgeId,
                               @Deprecated String configId,
                               @Deprecated String model) {
}
