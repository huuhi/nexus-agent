package com.huzhijian.nexusagentweb.vo;

import com.huzhijian.nexusagentweb.em.MessageType;
import lombok.Builder;
import lombok.Data;

import java.awt.*;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/3/29
 * 说明:
 */
@Builder
@Data
public class MessageVO {
    private String type;
    private String content;
    private String thinking;
//    private UserMessageVO userMessageVO;
//    工具执行结果
    private ToolResultVO toolResultVO;
//    工具请求参数
    private List<ToolRequestVO> toolRequestList;
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
