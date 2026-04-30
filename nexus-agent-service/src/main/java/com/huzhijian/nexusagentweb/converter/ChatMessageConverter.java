package com.huzhijian.nexusagentweb.converter;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.context.MessageMetadataContext;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.em.UserMessageType;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.huzhijian.nexusagentweb.content.MetadataKeyContent.*;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/26
 * 说明:
 */
@Component
@Slf4j
public class ChatMessageConverter {
    private final FileUtils fileUtils;

    public ChatMessageConverter(FileUtils fileUtils) {
        this.fileUtils = fileUtils;
    }
    public List<Content> toContents(List<ChatUserMessage> messages) throws ClientException, IOException {
        List<Content> contents=new ArrayList<>();
        List<Map<String, Object>> attachedFiles=new ArrayList<>();
        for (ChatUserMessage message : messages) {
            Map<String, Object> metadata = message.metadata();
            switch (message.type()){
                case UserMessageType.TEXT -> contents.add(TextContent.from(message.content()));
                case UserMessageType.FILE -> {
                    attachedFiles.add(metadata);
                    //解析
                    String url = metadata.get(FILE_URL).toString();
                    SysFile knowledgeFile = SysFile.builder().fileUrl(url).extension(metadata.get(FILE_TYPE).toString()).build();
                    Document document = fileUtils.getDocument(knowledgeFile);
                    String fileText = """
                    文件%s,的内容：%s
                    """.formatted(metadata.get(FILE_NAME),document.toTextSegment().text());
                    contents.add(TextContent.from(fileText));
                }
                case UserMessageType.IMAGE -> {
//                    如果是图片，不彻底ImageContent，防止token计算错误，将url添加到TextContent中即可
                    String url = metadata.get(FILE_URL).toString();
                    attachedFiles.add(metadata);
                    contents.add(TextContent.from("用户传递的图片url:"+url));
                }
            }
        }
        MessageMetadataContext.set(Map.of(ATTACHED_FILES, attachedFiles));
        return contents;
    }
    public String extractFirstText(List<ChatUserMessage> messages){
        //  理论上，用户消息都没有的话，应该在前面就处理了（抛出错误）
        return messages.stream()
                .filter(message ->
                        message.type().equals(UserMessageType.TEXT))
                .findFirst()
                .orElse(ChatUserMessage.builder().content("").build()).content();
    }
}
