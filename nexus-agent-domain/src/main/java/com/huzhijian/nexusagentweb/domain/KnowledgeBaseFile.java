package com.huzhijian.nexusagentweb.domain;


import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 
 * @TableName knowledge_base_file
 */
@TableName(value ="knowledge_base_file")
@Data
public class KnowledgeBaseFile {
    /**
     * 
     */
    private Integer knowledgeBaseId;

    /**
     * 
     */
    private Long fileId;

    /**
     * 
     */
    private Object status;

    /**
     * 
     */
    private String failReason;
}