package com.huzhijian.nexusagentweb.domain;


import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;

import lombok.Builder;
import lombok.Data;

/**
 * 用户表
 * @TableName chat_memory
 */
@TableName(value ="chat_memory")
@Data
@Builder
public class ChatHistory {
    /**
     * 
     */
    @TableId
    private Long id;

    /**
     * 用户ID，方便全局查找
     */
    private Long userId;

    /**
     * 内容
     */
    private Object content;

    /**
     * 消息类型
     */
    private Object type;

    /**
     * 
     */
    private Date createAt;

    /**
     * 会话ID
     */
    private Object sessionId;
}