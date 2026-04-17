package com.huzhijian.nexusagentweb.domain;


import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.typehandler.PgEnumTypeHandler;
import lombok.Builder;
import lombok.Data;

/**
 * 
 * @TableName file
 */
@TableName(value ="file")
@Data
@Builder
public class KnowledgeFile {
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
    private String fileUrl;

    /**
     * 
     */
    private String fileName;

    /**
     * 
     */
    private Long fileSize;

    /**
     * 
     */
    private String failReason;

    /**
     * 
     */
    @TableField(value = "upload_status",typeHandler = PgEnumTypeHandler.class)
    private UploadStatus uploadStatus;

    /**
     * 
     */
    private Date createTime;

    /**
     * 
     */
    private String extension;
}