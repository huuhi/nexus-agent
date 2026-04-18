package com.huzhijian.nexusagentweb.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 
 * @TableName chat_history_list
 */
@TableName(value ="chat_history_list")
@Data
@Builder
@AllArgsConstructor

public class ChatHistoryList {
    /**
     * 
     */
    @TableId
    private String sessionId;

    /**
     * 
     */
    private Long userId;

    /**
     * 
     */
    private String title;

    /**
     * 
     */
    private Date createTime;

    /**
     * 
     */
    private Date updateTime;
}