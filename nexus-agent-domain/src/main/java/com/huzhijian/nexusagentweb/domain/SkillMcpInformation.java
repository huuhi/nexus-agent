package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 * @TableName skill_mcp_information
 */
@TableName(value ="skill_mcp_information")
@Data
public class SkillMcpInformation {
    /**
     * 
     */
    @TableId
    private Long id;

    /**
     * 
     */
    private String name;

    /**
     * 
     */
    private Boolean isMcp;

    /**
     * 
     */
    private String sourcePath;

    /**
     * 
     */
    private Long userId;

    /**
     * 
     */
    private Boolean isPublic;
}