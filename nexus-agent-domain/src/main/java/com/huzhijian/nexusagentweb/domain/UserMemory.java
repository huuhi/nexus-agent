package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 用户长期记忆
 * @TableName user_memory
 */
@TableName(value ="user_memory")
@Data
@Builder
public class UserMemory {
    /**
     * 
     */
    @TableId
    private Long id;

    /**
     * 
     */
    private Long userId;

    /**
     * 
     */
    private String content;

    /**
     * 
     */
    private String category;

    /**
     * 
     */
    private Object embedding;

    /**
     * 
     */
    private Date createAt;

    /**
     * 
     */
    private Date updateAt;
}