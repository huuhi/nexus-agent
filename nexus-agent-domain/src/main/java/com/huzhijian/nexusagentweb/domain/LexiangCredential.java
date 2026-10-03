package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

/**
 * 乐享知识库接入凭证
 *
 * @TableName lexiang_credential
 */
@TableName(value = "lexiang_credential")
@Data
@Builder
public class LexiangCredential {

    @TableId
    private Long id;

    /**
     * nexus-agent 的用户 id（一个用户只保留一条）
     */
    private Long userId;

    /**
     * 乐享 AppKey。明文 —— 它是标识符而不是密钥，且换 access_token 时要原样传回。
     */
    private String appKey;

    /**
     * 乐享 AppSecret。<b>密文</b>，由 EncryptorFactory + 用户 salt 加密后落库。
     * 任何接口响应都不得回显此字段。
     */
    private String appSecret;

    /**
     * 发起检索的成员账号，作为 x-staff-id 请求头。
     * <p>
     * 乐享的所有 AI 搜索与写操作都强制要求该头，且<b>响应只返回该成员有权限的内容</b>，
     * 所以它同时是权限隔离维度。填固定值 {@code system-bot} 时只能检索全公司公开的知识。
     */
    private String staffId;

    /**
     * 默认检索知识库所属的团队 id（乐享层级 team → space → entry）
     */
    private String defaultTeamId;

    /**
     * 默认检索的知识库 id，对应 ai/search 的 targets[{type:space,id}]
     */
    private String defaultSpaceId;

    /**
     * 乐观锁与审计用，保存时由 Service 维护
     */
    private java.sql.Timestamp createdAt;

    private java.sql.Timestamp updatedAt;
}
