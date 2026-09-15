package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 系统日志表
 * @TableName system_log
 */
@TableName(value ="system_log")
@Data
@Builder
public class SystemLog {
    /**
     * 
     */
    @TableId
    private Long id;

    /**
     * 具体内容，比如方法名等
     */
    private Object content;

    /**
     * AI/System
     */
    private String type;

    /**
     * AI信息
     */
    private String aiMessage;

    /**
     * 方法耗时
     */
    private Integer costMs;

    /**
     * 
     */
    private Date createAt;
}