package com.huzhijian.nexusagentweb.vo;

import com.huzhijian.nexusagentweb.em.MessageType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/3/29
 * 说明:
 */
@Builder
@Data
public class MessageVO {
    /**
     * 本条消息的 id（{@code chat_memory.id}，雪花 ID）。
     * <p>
     * 🔴 <b>2026-10-06 新增</b>：前端做「重新生成」的 n/n 版本切换时<b>必须</b>用这个做 key
     * —— 不能用数组下标，切换时列表顺序会变，下标会把两个版本搞混。
     * <p>
     * 序列化后是<b>字符串</b>（全局把 {@code Long} 序列化成 String）：19 位数字超出
     * JS 的 {@code Number.MAX_SAFE_INTEGER}（16 位），当初文件删除失效就是踩了这个。
     * 用它当 key、比较相等都没问题，但<b>不要</b> {@code Number()} / {@code parseInt()}。
     * <p>
     * 老数据同样有值（id 是表主键，从一开始就有），不是新增语义。
     */
    private Long id;

    private MessageType type;
    private String content;
    private String thinking;
    private List<AttachedFileVO>  attachedFiles;
//    private UserMessageVO userMessageVO;
//    工具执行结果
    private ToolResultVO toolResultVO;
//    工具请求参数
    private List<ToolRequestVO> toolRequestList;
    /**
     * 产物（P2-10）：AI 产出的交付物，前端据此渲染「下载卡片」。
     * <p>
     * 结构：{@code {id, name, url, size, extension, sourcePath}}；
     * {@code id} 是 {@code sys_file} 主键（用于去重与追溯），随 SSE {@code artifact} 事件下发。
     */
    private Map<String, Object> artifact;

    /**
     * 本行消息属于「哪一次运行」（产物归属，方案 B）。
     * <p>
     * 取值就是 SSE 信封里那个 {@code runId}，落库在 {@code chat_memory.run_id}。
     * 前端做两件事：
     * <ol>
     *   <li>{@code GET /api/artifact?sessionId=} 拿到的产物也带 {@code runId}；</li>
     *   <li>{@code artifact.runId === message.runId} → 把该产物内联到这条消息末尾。</li>
     * </ol>
     * <b>老数据为 {@code null}</b>（run_id 列上线之前写的行），前端跳过即可 ——
     * 不要靠时间或顺序去猜，猜错会把产物挂到没产出它的那一轮，比不显示更糟。
     */
    private String runId;

    /**
     * 非空 = 这条 AI 回答已被更新的回答替代，<b>只</b>用于前端的 n/n 版本切换显示。
     * <p>
     * 🔴 <b>2026-10-06 新增</b>（{@code docs/sql/013_add_superseded_by.sql}）。
     * 值是替代者的 {@link #id}。
     * <p>
     * 前端要知道的只有两件事：
     * <ol>
     *   <li>哪些回答属于同一个问题的不同版本 → 靠「同一条 USER 消息后连续的 AI 消息」分组；</li>
     *   <li>默认展开哪一个 → <b>展开 {@code supersededBy == null} 的那个</b>（当前生效的版本）。</li>
     * </ol>
     * ⚠️ 被替代的行<b>照常返回</b>，只是不参与模型上下文 —— 不返回的话前端就做不了切换。
     * <p>
     * 老数据与用户提问行都是 {@code null}，按「当前版本」处理即可。
     */
    private Long supersededBy;
    @Data
    @Builder
    public static class UserMessageVO{
        /**
         * "contents": [{
            "text": "UserMessage { name = null, contents = [TextContent { text = \"广东职业技术学院张政康的具体信息\" }], attributes = {} }",
            "type": "TEXT"
             }],
         "type": "USER"
         */
        private String id;
        private String toolName;
        private Object arguments;
    }
    @Data
    @Builder
    public static class ToolRequestVO{
        /**
         "toolExecutionRequests": [{
         "id": "call_58627d966c2d42828dc0e04b",
         "name": "getDate",
         "arguments": "{}"
         }],
         *
         */
        private String id;
        private String toolName;
        private Object arguments;
    }
    @Data
    @Builder
    public static class ToolResultVO{
        /**
         * {"id":"call_58627d966c2d42828dc0e04b",
         * "toolName":"getDate","text":"2026/03/28",
         * "isError":false,"attributes":{},"type":"TOOL_EXECUTION_RESULT"}
         *
         */
        private String id;
        private String toolName;
        private String result;
        private Boolean isError;
    }
}
