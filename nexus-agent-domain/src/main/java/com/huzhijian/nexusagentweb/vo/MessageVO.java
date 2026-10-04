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
