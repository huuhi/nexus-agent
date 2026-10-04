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

    /**
     * 写入该消息那次运行的 runId（产物归属，方案 B）。
     * <p>
     * 对应 {@code docs/sql/011_add_run_id.sql}。与 {@code sys_file.run_id} 配套：
     * 前端把「历史行的 runId」与「产物的 runId」按字符串相等匹配，
     * 从而确定「哪个文件是哪一轮对话产出的」（刷新页面后依然成立）。
     * <p>
     * 本列上线前落库的历史行为 {@code null}，前端按「归属不明」跳过，不会冒充某一轮。
     */
    private String runId;
}