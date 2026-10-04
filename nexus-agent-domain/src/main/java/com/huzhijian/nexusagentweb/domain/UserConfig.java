package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

/**
 * 用户SKILL关系模型
 * @TableName user_config
 *
 * <p><b>⚠️ 2026-10-04 安全修复：三个敏感字段全部 {@code @JsonIgnore}。</b>
 * <p>
 * 本实体<b>只该在服务层内部流转</b>，绝不该出现在任何 HTTP 响应里 ——
 * 它装的是用户的 LLM API Key（密文）、MCP Token（密文）和
 * <b>加解密主密钥的派生盐</b>。三样东西任意泄漏一条都够把用户凭据解开。
 * <p>
 * 之前这里没有任何防护：只要有人图省事写一句
 * {@code return Result.ok(userConfigService.getById(userId))}
 * （{@code UserConfigService extends IService<UserConfig>}，这是最自然的写法），
 * 密文就整包出去了。而且这种泄漏<b>不会报错</b>、不会被扫描工具发现，
 * 只能靠 review 抓到 —— 属于典型的"结构性风险"。
 * <p>
 * {@code @JsonIgnore} 是从<b>结构上</b>关掉这条路径：无论谁怎么写，
 * Jackson 都不会吐出这三个字段，真要输出得显式加 {@code @JsonProperty} 才能恢复。
 * 需要在服务层读值不受影响（Jackson 只管序列化/反序列化，不影响 getter 调用）。
 *
 * @author windows
 * @createDate 2026-04-26
 */
@TableName(value ="user_config")
@Data
@Builder
public class UserConfig {
    /**
     *
     */
    @TableId
    private Long userId;


    /**
     * LLM API 配置，jsonb 数组，元素为 {@code APIConfig}（其中 apiKey 已加密）。
     * <p>⚠️ 含用户全部模型凭据，禁止序列化。
     * */
//    @TableField(typeHandler = JacksonTypeHandler.class)
    @JsonIgnore
    private String llmApiToken;

    /**
     * MCP 服务 token（已加密）。
     * <p>⚠️ 禁止序列化。
     */
    @JsonIgnore
    private String mcpToken;

    /**
     * 该用户凭据的加解密盐。
     * <p>⚠️ 泄漏它等于把上面两列的密文变得可被离线爆破，禁止序列化。
     */
    @JsonIgnore
    private String salt;
}
