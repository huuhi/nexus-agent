package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存乐享接入凭证的请求。
 *
 * @param appKey          乐享 AppKey
 * @param appSecret       乐享 AppSecret（服务端加密后落库，响应绝不回显）
 * @param staffId         发起检索的成员账号，即 x-staff-id
 * @param defaultTeamId   默认检索知识库所属团队 id（可空，为空则用全站范围）
 * @param defaultSpaceId  默认检索的知识库 id（可空，为空则检索全站）
 */
public record LexiangCredentialDTO(@NotBlank(message = "AppKey 不能为空！") String appKey,
                                   @NotBlank(message = "AppSecret 不能为空！") String appSecret,
                                   @NotBlank(message = "成员账号（staffId）不能为空！") String staffId,
                                   String defaultTeamId,
                                   String defaultSpaceId) {
}
