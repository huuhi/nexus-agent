package com.huzhijian.nexusagentweb.vo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadFailCode;
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
     * 失败原因的**机器可读**码（2026-10-07 新增）。
     * <p>
     * 成功时为 {@code null}。前端判断失败类型请用这个字段，
     * <b>不要去匹配 {@code failReason} 的中文文案</b>（文案会改，码不会）。
     * 取值见 {@link UploadFailCode}。
     */
    private UploadFailCode failCode;

    /**
     *
     */
    @TableField(value = "upload_status",typeHandler = PgEnumTypeHandler.class)
    private UploadStatus uploadStatus;

    /**
     *
     */
    private Date createTime;


    private BizType bizType;
    /**
     *
     */
    private String extension;
}
