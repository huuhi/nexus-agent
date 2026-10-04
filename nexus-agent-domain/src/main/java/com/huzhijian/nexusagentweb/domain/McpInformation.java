package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

/**
 * MCP配置信息
 * @TableName mcp_information
 */
@TableName(value ="mcp_information")
@Data
@Builder
public class McpInformation {
    /**
     * 
     */
    @TableId
    private Long id;

//    MCP 服务 唯一标识
    private String strId;

    /**
     * 
     */
    private String name;

    /**
     * 
     */
    private String url;

    /**
     * 
     */
    private String description;

    private String logoUrl;

    /**
     * 自定义请求头，**存 jsonb 列的 JSON 文本**。
     * <p>
     * ⚠️ 2026-10-04 从 {@code Object} 改成 {@code String}。原来声明成 Object 有两个后果：
     * <ol>
     *   <li>写：MyBatis 找不到对应 TypeHandler，参数类型不确定，只能靠 XML 里的
     *       {@code ::jsonb} 硬转；一旦漏了转换 PG 就报
     *       「column is of type jsonb but expression is of type character varying」</li>
     *   <li>读：MyBatis-Plus 自动生成的 select 走 {@code UnknownTypeHandler} →
     *       {@code rs.getObject()} 返回的是 PostgreSQL 的 {@code PGobject}，
     *       序列化成 JSON 会变成 <code>{"type":"jsonb","value":"{...}"}</code> 这种
     *       嵌套壳子，前端拿到的是字符串而不是头对象。</li>
     * </ol>
     * 改成 String 后与 {@code UserConfig.llmApiToken} 保持同一范式：
     * {@code rs.getString()} 直接拿到 jsonb 文本，写入侧统一在 XML 里用 {@code ::jsonb}。
     * 对外暴露时由 {@code McpInformationServiceImpl} 解析成对象。
     */
    private String header;

    /**
     * 
     */
    private Long userId;

    /**
     * MCP类型
     */
    private String type;

    private Boolean available;
}