package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 * @TableName knowledge_base
 */
@TableName(value ="knowledge_base")
@Data
public class KnowledgeBase {
    /**
     * 
     */
    @TableId
    private Integer id;

    /**
     * 
     */
    private Long userId;

    /**
     * 
     */
    private String name;

    /**
     * 
     */
    private String describe;

    /**
     * 
     */
    private Boolean isPublic;

    /**
     * 
     */
    private String languageCode;
}