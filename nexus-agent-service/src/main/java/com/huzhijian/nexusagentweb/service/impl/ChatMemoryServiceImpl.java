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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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

    /**
     * 兼容**存量数据**的包裹标记：无论新旧格式（两括号带 id / 三括号无 id），
     * 只要是配对的 FILE/IMAGE 包裹块就从文本里剥掉。
     * <p>
     * 背景：图片消息曾用「URL 包在 TextContent 里」的方案，标记就被存进了 chat_memory；
     * 2026-10-03 改用真正的 {@code ImageContent} 后，新消息不再产生这些文本，
     * 但历史库里已经存了的还在 —— 读取时在这里统一剥掉，前端才不会显示出一坨标记。
     */
    private static final Pattern LEGACY_WRAPPER = Pattern.compile(
            "<<{2,3}(IMAGE|FILE)_START( id=\"[^\"]*\")?>>{2,3}.*?<<{2,3}\\1_END>>{2,3}",
            Pattern.DOTALL
    );

    /** 剥掉文本里残留的文件/图片包裹标记（存量数据兼容），并收敛首尾空白 */
    private static String stripLegacyWrappers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return LEGACY_WRAPPER.matcher(text).replaceAll("").strip();
    }
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
    public List<ChatHistory> getRecentForChat(Object sessionId, Long userId, int limit) {
//        ⚠️ limit <= 0 必须在这里分流：SQL 的 LIMIT 0 会返回 0 行、LIMIT 负数直接报错，
//        而调用方的语义是「不限制」。别把它透传进 SQL。
        if (limit <= 0) {
            return mapper.getAllByMemoryIdAndUserId(sessionId, userId);
        }
        return mapper.getRecentForChat(sessionId, userId, limit);
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
//        逐行处理（2026-10-05 改）：产物归属要求**每一行**历史带上它自己的 runId，
//        所以不能再「先映射成 ChatMessage 列表、再统一转 VO」——那样行上的 runId 就丢了。
        List<MessageVO> result = new ArrayList<>(chatHistories.size());
        for (ChatHistory entity : chatHistories) {
            ChatMessage msg = toChatMessage(entity);
            if (msg == null) {
                continue;
            }
            MessageVO vo = toMessageVO(msg);
            if (vo == null) {
                continue;
            }
//            老数据（run_id 列上线前写的行）这里就是 null，前端按「归属不明」跳过
            vo.setRunId(entity.getRunId());
            result.add(vo);
        }
        return result;
    }

    /**
     * 一行历史 → 一条 langchain4j 消息。脏数据返回 {@code null}（已记日志），由调用方跳过。
     */
    private ChatMessage toChatMessage(ChatHistory entity) {
        // content 是 jsonb 列，历史脏数据可能为 null：跳过而不是让整段会话 500
        if (entity.getContent() == null) {
            log.warn("历史消息 content 为空，已跳过。id={}", entity.getId());
            return null;
        }
        String content = entity.getContent().toString();
        if ("TOOL_EXECUTION_RESULT".equals(entity.getType())) {
            try {
                content = toStandardToolExecutionResult(content);
            } catch (JsonProcessingException e) {
                // 单条工具结果解析失败不该连累整段历史：记日志后用原始内容兜底
                log.warn("TOOL_EXECUTION_RESULT 标准化失败，回退原始内容。id={}", entity.getId(), e);
            }
        }
        return ChatMessageDeserializer.messageFromJson(content);
    }

    /**
     * 一条消息 → 前端要的 VO。未知类型返回 {@code null}（不出现在历史里）。
     */
    private MessageVO toMessageVO(ChatMessage msg) {
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
                        messageVOBuilder.content(stripLegacyWrappers(text));
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
//                                图片走 ImageContent（2026-10-03 起）：文本里不再出现，前端用 attachedFiles 渲染
                                continue;
                            }
                            String text = textContent.text();
                            if (isFileOrImageWrapper(text)) {
                                continue;
                            }
                            textBuilder.append(text);
                        }
//                        存量数据可能把文本和包裹标记存在同一个 content 里，统一再剥一次
                        messageVOBuilder
                                .content(stripLegacyWrappers(textBuilder.toString()))
                                .attachedFiles(attachedFiles);
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
                    return null;
                }
            }
            return messageVOBuilder.build();
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




