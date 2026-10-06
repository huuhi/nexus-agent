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

    /**
     * 若非空 = 这条 <b>AI 回答</b>已被另一条更新的回答替代（存替代者的 {@code id}）。
     * <p>
     * 对应 {@code docs/sql/013_add_superseded_by.sql}，服务于「重新生成」：
     * 同一个问题可以有多版回答，但只有当前生效的那版该进模型上下文 ——
     * 否则模型会同时看到「问过两遍、答过两遍」，既白烧 token 又让它困惑，
     * 而且前端的 n/n 版本切换会形同虚设（切回去接着聊，模型记得的仍是另一版）。
     * <p>
     * 三处用法必须一致：
     * <ol>
     *   <li>记忆加载（{@code getActiveForChat}）**排除**本列非空的行 → 模型只看到当前版本；</li>
     *   <li>历史接口**全部返回**并带上本列 → 前端据此做 n/n 切换（被替代的只是不参与模型上下文，
     *       仍要显示给用户看）；</li>
     *   <li>用户提问行永远是 {@code null}。</li>
     * </ol>
     * 本列上线前的历史数据为 {@code null}，一律视为「当前版本」，行为与上线前一致。
     */
    private Long supersededBy;
}