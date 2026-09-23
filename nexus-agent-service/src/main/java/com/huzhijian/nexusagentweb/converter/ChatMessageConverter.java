package com.huzhijian.nexusagentweb.converter;

import com.aliyuncs.exceptions.ClientException;
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
 * 说明: 把前端传来的用户消息转换成 LangChain4j 的 Content 列表。
 * <p>
 * 附件元数据（文件名、URL 等）不再写进 ThreadLocal，而是随返回值一起交给调用方，
 * 由调用方放进 {@code RunContext} 显式传递到流式回调线程。
 * 原因见 {@link com.huzhijian.nexusagentweb.context.RunContext}。
 */
@Component
@Slf4j
public class ChatMessageConverter {
    private final FileUtils fileUtils;

    public ChatMessageConverter(FileUtils fileUtils) {
        this.fileUtils = fileUtils;
    }

    /**
     * 转换结果。
     *
     * @param contents 给模型的 Content 列表
     * @param metadata 需要持久化到用户消息 attributes 的元数据（当前只有附件列表）。
     *                 即使没有附件也保留 {@code attached_files: []}，与历史行为一致。
     */
    public record ConvertedMessage(List<Content> contents, Map<String, Object> metadata) {
    }

    public ConvertedMessage toContents(List<ChatUserMessage> messages) throws ClientException, IOException {
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
                    %s
                    文件%s,的内容：%s;
                    %s
                    """.formatted(FILE_START,metadata.get(FILE_NAME),document.toTextSegment().text(),FILE_END);
                    contents.add(TextContent.from(fileText));
                }
                case UserMessageType.IMAGE -> {
//                    如果是图片，不彻底ImageContent，防止token计算错误，将url添加到TextContent中即可
                    String url = metadata.get(FILE_URL).toString();
                    attachedFiles.add(metadata);
                    String imageUrl= """
                           %s
                            用户传递的图片url: %s;
                           %s
                           """.formatted(IMAGE_START,url,IMAGE_END);
                    contents.add(TextContent.from(imageUrl));
                }
            }
        }
        return new ConvertedMessage(contents, Map.of(ATTACHED_FILES, attachedFiles));
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
