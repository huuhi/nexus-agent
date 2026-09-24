package com.huzhijian.nexusagentweb.domain;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.typehandler.PgEnumTypeHandler;
import lombok.Builder;
import lombok.Data;

/**
 * 
 * @TableName file
 */
@TableName(value ="sys_file")
@Data
@Builder
public class SysFile {
    /**
     * 
     */
    @TableId(type = IdType.ASSIGN_ID)
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

    private BizType bizType;

    /**
     * 归属会话 ID（P2-10，可空）。
     * <p>
     * 仅 {@link BizType#ARTIFACT} 会写它：产物需要按会话追溯与清理
     * （用户上传的附件走别的关系，不依赖本列）。
     */
    private String sessionId;
}