package com.huzhijian.nexusagentweb.converter;

import com.aliyuncs.exceptions.ClientException;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.em.UserMessageType;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
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
        List<Content> contents = new ArrayList<>();
        List<Map<String, Object>> attachedFiles = new ArrayList<>();
        for (ChatUserMessage message : messages) {
            Map<String, Object> metadata = message.metadata();
            switch (message.type()) {
                case UserMessageType.TEXT -> contents.add(TextContent.from(message.content()));
                case UserMessageType.FILE -> {
                    attachedFiles.add(metadata);
                    //解析
                    String url = requireMetadata(metadata, FILE_URL, UserMessageType.FILE.name());
                    String extension = resolveExtension(metadata, url);
                    SysFile knowledgeFile = SysFile.builder().fileUrl(url).extension(extension).build();
                    Document document = fileUtils.getDocument(knowledgeFile);
                    String fileName = textOrDefault(metadata, FILE_NAME, "未命名文件");
                    String fileText = """
                    %s
                    文件%s,的内容：%s;
                    %s
                    """.formatted(FILE_START, fileName, document.toTextSegment().text(), FILE_END);
                    contents.add(TextContent.from(fileText));
                }
                case UserMessageType.IMAGE -> {
//                    2026-10-03：改用真正的 ImageContent —— 这是多模态模型看到图片的唯一途径。
//                    原实现把 URL 包在 TextContent 里（注释说"防止 token 计算错误"），
//                    结果是模型收到的只是一串 URL 文字：支持视觉的模型也说"我没看到图"，
//                    然后拿沙盒代码去瞎折腾。token 计算的坑已由 MultimodalTokenCountEstimator 解决。
                    String url = requireMetadata(metadata, FILE_URL, UserMessageType.IMAGE.name());
                    attachedFiles.add(metadata);
                    contents.add(ImageContent.from(url));
                }
            }
        }
        return new ConvertedMessage(contents, Map.of(ATTACHED_FILES, attachedFiles));
    }

    /**
     * 取附件 metadata 里的**必需**字段。
     * <p>
     * 2026-10-03：原先直接 {@code metadata.get(FILE_URL).toString()} —— 前端少传一个字段
     * 就是 {@code NullPointerException} + 500，日志里只有
     * {@code Cannot invoke "Object.toString()" because the return value of "Map.get(Object)" is null}，
     * 完全看不出是**哪个字段**没传、也不说是哪条消息。
     * 现在改成抛 {@link ValidationException}（会被 GlobalExceptionHandler 转成 400），
     * 把「消息类型 + 缺的字段名 + 正确写法」一次说清。
     */
    private String requireMetadata(Map<String, Object> metadata, String key, String type) {
        if (metadata == null) {
            throw new ValidationException(
                    "消息类型是 " + type + "，但没有传 metadata。"
                            + "附件消息必须带 metadata，其中必需字段："
                            + FILE_URL + "（文件/图片的访问地址）、" + FILE_TYPE + "（扩展名，FILE 类型必需）、"
                            + FILE_NAME + "（文件名，可选）。字段名是驼峰，注意不要写成 file_url。");
        }
        Object value = metadata.get(key);
        if (value == null) {
            throw new ValidationException(
                    "附件消息（type=" + type + "）的 metadata 缺少字段 '" + key + "'。"
                            + "当前传了这些字段：" + metadata.keySet() + "。"
                            + "必需字段：fileUrl（访问地址）、extension（扩展名，FILE 必需）、fileName（文件名，可选）。"
                            + "⚠️ 是驼峰命名，不是下划线。");
        }
        return value.toString();
    }

    /**
     * 文档解析用的扩展名：优先取 metadata 的 {@code extension}，没有就从 {@code fileUrl} 推断。
     * <p>
     * 为什么不硬性要求 extension：它只是选解析器的依据，而 fileUrl 里几乎总有扩展名。
     * 前端少传一个字段就要改一次代码、重新联调，代价太大；推断不出来再报错也不迟。
     */
    private String resolveExtension(Map<String, Object> metadata, String url) {
        Object declared = metadata == null ? null : metadata.get(FILE_TYPE);
        if (declared != null && !declared.toString().isBlank()) {
            return declared.toString();
        }
        String path = url.split("\\?")[0];
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot > slash + 1 && dot < path.length() - 1) {
            String inferred = path.substring(dot + 1);
            log.warn("metadata 里没有 extension，从 fileUrl 推断为 '{}'（建议前端还是显式传 extension）", inferred);
            return inferred;
        }
        throw new ValidationException(
                "无法确定文档扩展名：metadata 里没有 '" + FILE_TYPE + "'，也没法从 fileUrl '" + url + "' 推断。"
                        + "请在 metadata 里带上 extension（如 xlsx / docx / pdf）。");
    }

    /** 取可选字段，缺失时用默认值兜底，不抛异常 */
    private String textOrDefault(Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        return value == null ? fallback : value.toString();
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
