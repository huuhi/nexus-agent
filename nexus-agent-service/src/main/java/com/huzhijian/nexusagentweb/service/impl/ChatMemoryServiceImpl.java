package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.AttachedFileVO;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.data.message.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.huzhijian.nexusagentweb.content.MetadataKeyContent.*;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Service实现
* @createDate 2026-04-16 20:02:49
*/
@Service
@Slf4j
public class ChatMemoryServiceImpl extends ServiceImpl<ChatMemoryMapper, ChatHistory>
    implements ChatMemoryService{

    @Resource
    private ChatMemoryMapper mapper;
    private static final ObjectMapper MAPPER = new ObjectMapper();
//    文件
    private final Pattern FILE_PATTERN = Pattern.compile(
            "<<FILE_START id=\"(.*?)\">>.*?<<FILE_END>>",
            Pattern.DOTALL
    );
//    图片
    private final Pattern IMAGE_PATTERN = Pattern.compile(
            "<<IMAGE_START id=\"(.*?)\">>.*?<<IMAGE_END>>",
            Pattern.DOTALL
    );
    @Override
    public List<ChatHistory> getByMemoryId(Object memory) {
//        输入任意字符，则过滤工具消息
        return mapper.getAllByMemoryId(memory);
    }

    @Override
    public List<ChatHistory> getByMemoryIdAndUserId(Object memory, Long userId) {
//        对话链路读记忆专用：必须带 user_id，否则会读到别人的会话
        return mapper.getAllByMemoryIdAndUserId(memory, userId);
    }

    @Override
    public String getLastMessageJson(Object sessionId, Long userId) {
        return mapper.getLastContentByMemoryId(sessionId, userId);
    }

    @Override
    public List<String> getRecentMessageJson(Object sessionId, Long userId, int limit) {
        return mapper.getRecentContents(sessionId, userId, limit);
    }

    @Override
    public void delByMemoryId(Object memoryId) {
        mapper.delAllByMemoryId(memoryId);
    }

    @Override
    public void insertBatch(List<ChatHistory> list,Long userId) {
        if (list.isEmpty()){
            log.debug("插入失败,消息为空");
            return;
        }
        mapper.insertBatch(list, userId);
    }
    @Override
    public int getCountBySessionID(String sessionId) {
        return mapper.getCountByMemoryId(sessionId);
    }

    @Override
    public List<ChatMemorySearchHit> searchHits(Long userId, String pattern, int limit) {
        // userId 由调用方保证非空（会话搜索走的是已登录接口）；pattern/limit 也已在那边收敛过
        return mapper.searchHits(userId, pattern, limit);
    }

    @Override
    public List<MessageVO> getHistoryBySessionId(String sessionId) {
//        越权修复：对外读取历史必须限定当前登录用户，否则知道 sessionId 就能读他人会话
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录！");
        }
        List<ChatHistory> chatHistories = mapper.getAllByMemoryIdAndUserId(sessionId, userId);
        if (chatHistories==null||chatHistories.isEmpty()) return List.of();
        List<ChatMessage> history = chatHistories.stream().map(entity ->{
            String content = entity.getContent().toString();
            if (entity.getType().equals("TOOL_EXECUTION_RESULT")){
                try {
                    content = toStandardToolExecutionResult(content);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            return ChatMessageDeserializer
                .messageFromJson(content);
        }).toList();
        return history.stream().flatMap(msg->{
            MessageVO.MessageVOBuilder messageVOBuilder = MessageVO
                    .builder();
            switch (msg) {
                case UserMessage userMessage -> {
                    messageVOBuilder.type(MessageType.USER);
//                    这里要考虑之后，图文并发的时候，怎么处理
//                    暂时只考虑单文本
//                    解决方案：添加标识符区分

                    if (userMessage.hasSingleText()) {
                        String text = userMessage.singleText();
                        /*这里获取到了:
                        UserMessage { name = null, contents = [TextContent { text = "text" }], attributes = {} }
                        要进行转换
                        */
                        messageVOBuilder.content(text);
                    }else{
//                        说明有文件/图片等其他内容
                        Map<String, Object> attributes = userMessage.attributes();
                        log.debug("attributes:{}",attributes);

//                        附件元数据（可能为 null，用空集合兜底）
                        Object attachedFilesRaw = attributes.get(ATTACHED_FILES);
                        List<AttachedFileVO> attachedFiles = attachedFilesRaw == null
                                ? List.of()
                                : JSONUtil.toList(JSONUtil.toJsonStr(attachedFilesRaw), AttachedFileVO.class);

//                        文本内容：过滤掉文件/图片的包裹片段，只保留用户真正输入的文本。
//                        注意：必须用 instanceof 判断——历史上这里直接强转 TextContent，
//                        遇到图片等非文本内容会抛 ClassCastException。
                        StringBuilder textBuilder = new StringBuilder();
                        for (Content content : userMessage.contents()) {
                            if (!(content instanceof TextContent textContent)) {
                                continue;
                            }
                            String text = textContent.text();
                            if (isFileOrImageWrapper(text)) {
                                continue;
                            }
                            textBuilder.append(text);
                        }
                        messageVOBuilder.content(textBuilder.toString()).attachedFiles(attachedFiles);
                    }


//                	"contents": [{
                    //		"text": "UserMessage { name = null, contents = [TextContent { text = \"广东职业技术学院张政康的具体信息\" }], attributes = {} }",
                    //		"type": "TEXT"
                    //	}],
                    //	"type": "USER"
                }
                case AiMessage aiMessage -> {
                    messageVOBuilder.type(MessageType.AI);
                    messageVOBuilder.content(aiMessage.text());
                    messageVOBuilder.thinking(aiMessage.thinking());
//                toolExecutionRequests
                    List<MessageVO.ToolRequestVO> requestVOList = aiMessage.toolExecutionRequests().stream().map(request -> MessageVO.ToolRequestVO.builder()
                            .toolName(request.name())
                            .id(request.id())
                            .arguments(request.arguments()).build()).toList();
                    messageVOBuilder.toolRequestList(requestVOList);
                }
                case ToolExecutionResultMessage toolResult -> {
                    messageVOBuilder.type(MessageType.TOOL_EXECUTION_RESULT);
                    MessageVO.ToolResultVO resultVO = MessageVO.ToolResultVO.builder()
                            .isError(toolResult.isError())
                            .result(toolResult.text())
                            .toolName(toolResult.toolName())
                            .id(toolResult.id())
                            .build();
                    messageVOBuilder.toolResultVO(resultVO);
                }
                default -> {
                    log.info("其他类型，暂时不处理");
                    return Stream.empty();
                }
            }
            return Stream.of(messageVOBuilder.build());
        }).toList();
    }


    private static boolean isFileOrImageWrapper(String text) {
        return (text.startsWith(FILE_START) && text.endsWith(FILE_END))
                || (text.startsWith(IMAGE_START) && text.endsWith(IMAGE_END));
    }

    private static String toStandardToolExecutionResult(String rawJson) throws JsonProcessingException {
        ObjectNode root =(ObjectNode) MAPPER.readTree(rawJson);
        if (root.has("contents")&& root.get("contents").isArray()) {
            ArrayNode contents = (ArrayNode) root.get("contents");
            if (!contents.isEmpty() &&contents.get(0).has("text")){
                String text = contents.get(0).get("text").asText();
                root.put("text",text);
            }
            root.remove("contents");
        }
        if (!root.has("text")) {
            root.put("text","没有返回值");
        }
        return MAPPER.writeValueAsString(root);
    }
}




