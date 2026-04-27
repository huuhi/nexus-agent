package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

/**
 * 用户SKILL关系模型
 * @TableName user_config
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
     * 
     */
    private Object llmApiToken;

    /**
     * 
     */
    private Object mcpToken;

    /**
     * 
     */
    private Object userDefault;
}