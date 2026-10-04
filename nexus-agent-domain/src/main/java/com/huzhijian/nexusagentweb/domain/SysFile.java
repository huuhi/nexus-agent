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

    /**
     * 产出该文件那次运行的 runId（产物归属，方案 B）。
     * <p>
     * 对应 {@code docs/sql/011_add_run_id.sql}。前端用
     * {@code artifact.runId === message.runId} 把产物内联到「产出它的那一轮回答」末尾；
     * 对不上就只进右侧「成果文件」面板。
     * <p>
     * 语义是「**产出该文件的那次运行**」，不是当前请求的运行 —— 所以必须持久化，
     * 不能是进程内临时 id（否则重启后归不上）。本列上线前落库的产物为 {@code null}。
     */
    private String runId;
}