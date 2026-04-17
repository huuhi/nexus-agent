package com.huzhijian.nexusagentweb.domain;


import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.typehandler.PgEnumTypeHandler;
import lombok.Builder;
import lombok.Data;

/**
 * 
 * @TableName knowledge_base_file
 */
@TableName(value ="knowledge_base_file")
@Data
@Builder
public class KnowledgeBaseFile {
    /**
     * 
     */
    private Integer knowledgeBaseId;

    /**
     * 
     */
    private Long fileId;

    @TableField(value = "status",typeHandler = PgEnumTypeHandler.class)
    private UploadStatus status;

    /**
     * 
     */
    private String failReason;
}