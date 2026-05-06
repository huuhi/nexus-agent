package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.em.MessageType;
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

import static com.huzhijian.nexusagentweb.content.MetadataKeyContent.ATTACHED_FILES;

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

    @Override
    public List<ChatHistory> getByMemoryId(Object memory) {
//        输入任意字符，则过滤工具消息
        return mapper.getAllByMemoryId(memory);
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
    public List<MessageVO> getHistoryBySessionId(String sessionId) {
        List<ChatHistory> chatHistories = mapper.getAllByMemoryId(sessionId);
        if (chatHistories==null||chatHistories.isEmpty()) return List.of();
        List<ChatMessage> history = chatHistories.stream().map(entity -> ChatMessageDeserializer
                .messageFromJson(entity.getContent().toString())).toList();
        return history.stream().map(msg->{
            MessageVO.MessageVOBuilder messageVOBuilder = MessageVO
                    .builder();
            switch (msg) {
                case UserMessage userMessage -> {
//                  TODO  这里要考虑之后，图文并发的时候，怎么处理
//                    暂时只考虑单文本
                    if (userMessage.hasSingleText()) {
                        String text = userMessage.singleText();
                        /*这里获取到了:
                        UserMessage { name = null, contents = [TextContent { text = "text" }], attributes = {} }
                        要进行转换
                        */
                        messageVOBuilder.content(text);
                    }else{
//                        说明有文件等其他内容
                        Map<String, Object> attributes = userMessage.attributes();
                        log.debug("attributes:{}",attributes);
                        String attachedFilesJSON = JSONUtil.toJsonStr(attributes.get(ATTACHED_FILES));
                        log.debug("attachedFilesJSON:{}",attachedFilesJSON);
                        List<AttachedFileVO> list = JSONUtil.toList(attachedFilesJSON, AttachedFileVO.class);
                        List<Content> contents = userMessage.contents();
                        if (contents.getFirst() instanceof TextContent userText){
                            messageVOBuilder.content(userText.text()).type(MessageType.USER).attachedFiles(list);
                        }
//                        TODO 如果第一条不是文本消息怎么处理？如果文本消息是文件内容，不是用户消息怎么判断？怎么处理？
                    }


//                	"contents": [{
                    //		"text": "UserMessage { name = null, contents = [TextContent { text = \"广东职业技术学院张政康的具体信息\" }], attributes = {} }",
                    //		"type": "TEXT"
                    //	}],
                    //	"type": "USER"
                }
                case AiMessage aiMessage -> {
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
                    MessageVO.ToolResultVO resultVO = MessageVO.ToolResultVO.builder()
                            .isError(toolResult.isError())
                            .result(toolResult.text())
                            .toolName(toolResult.toolName())
                            .id(toolResult.id())
                            .build();
                    messageVOBuilder.toolResultVO(resultVO);
                }
                default -> log.info("其他类型，暂时不处理");
            }
            return messageVOBuilder.build();
        }).toList();
    }
}




