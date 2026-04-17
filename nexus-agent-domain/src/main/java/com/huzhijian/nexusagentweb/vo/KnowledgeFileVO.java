package com.huzhijian.nexusagentweb.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.typehandler.PgEnumTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
@Data
@AllArgsConstructor
public class KnowledgeFileVO {

    private Long id;

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
