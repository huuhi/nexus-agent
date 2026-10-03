package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.em.UserMessageType;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ChatMessageConverter} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 线上那个 NPE：带图片 / 带文档聊天直接 500，
 * 日志只有 {@code Cannot invoke "Object.toString()" because the return value of "Map.get(Object)" is null}，
 * 看不出是哪个字段没传。根因是前端文档里把字段名写成了 {@code url}，
 * 而后端要的是 {@code fileUrl}（驼峰），于是 {@code metadata.get("fileUrl")} 恒为 null。
 */
@DisplayName("ChatMessageConverter —— 附件消息缺字段时给明确报错，而不是 NPE")
class ChatMessageConverterTest {

    private FileUtils fileUtils;
    private ChatMessageConverter converter;

    @BeforeEach
    void setUp() {
        fileUtils = mock(FileUtils.class);
        converter = new ChatMessageConverter(fileUtils);
    }

    @Test
    @DisplayName("图片消息没传 metadata：报 400 并说明必需字段，不能 NPE")
    void imageWithoutMetadata() {
        List<ChatUserMessage> messages = List.of(
                ChatUserMessage.builder().type(UserMessageType.IMAGE).content("").metadata(null).build());

        ValidationException ex = assertThrows(ValidationException.class, () -> converter.toContents(messages));
        assertTrue(ex.getMessage().contains("fileUrl"), "报错里要说清期望的字段名：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("图片消息 metadata 里写成了 url（不是 fileUrl）：报 400 并列出缺哪个字段")
    void imageWithWrongKey() {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("url", "https://oss.../a.png"))
                .build());

        ValidationException ex = assertThrows(ValidationException.class, () -> converter.toContents(messages));
        assertTrue(ex.getMessage().contains("fileUrl"), "报错里要指出缺的是 fileUrl：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("文档缺 extension：从 fileUrl 推断（少一次前后端往返）")
    void fileWithoutExtensionInfersFromUrl() throws Exception {
        when(fileUtils.getDocument(any(SysFile.class))).thenReturn(Document.from("内容"));

        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", "https://oss.../a.xlsx?x-oss-signature=abc"))
                .build());

//        不抛异常即说明推断成功（URL 的 query 部分要能被剥掉）
        assertEquals(1, converter.toContents(messages).contents().size());
    }

    @Test
    @DisplayName("文档既没 extension、fileUrl 里也没有：报 400 说清原因")
    void fileWithoutAnyExtension() {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", "https://oss.../noext"))
                .build());

        ValidationException ex = assertThrows(ValidationException.class, () -> converter.toContents(messages));
        assertTrue(ex.getMessage().contains("extension"), "报错里要指出需要 extension：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("正常图片：产生真正的 ImageContent（多模态模型才能看到图），attached_files 保留原始 metadata")
    void imageOk() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("fileUrl", "https://oss.../a.png", "fileName", "a.png"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages);

        assertEquals(1, result.contents().size());
//        2026-10-03：必须是 ImageContent —— URL 文本模型是"看不到"的
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                dev.langchain4j.data.message.ImageContent.class, result.contents().get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attached =
                (List<Map<String, Object>>) result.metadata().get("attached_files");
        assertEquals(1, attached.size());
        assertEquals("https://oss.../a.png", attached.get(0).get("fileUrl"));
    }

    @Test
    @DisplayName("模型不支持视觉（vision=false）：图片降级为 URL 文本，不能发 ImageContent")
    void imageDegradesWhenModelHasNoVision() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("fileUrl", "https://oss.../a.png", "fileName", "a.png"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages, false);

        assertEquals(1, result.contents().size());
//        降级后必须是文本；发 ImageContent 会被不支持视觉的上游直接拒绝（400）
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                dev.langchain4j.data.message.TextContent.class, result.contents().get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attached =
                (List<Map<String, Object>>) result.metadata().get("attached_files");
//        附件元数据仍然保留，前端照样能渲染图片
        assertEquals(1, attached.size());
    }

    @Test
    @DisplayName("正常文档：调用解析器并把正文拼进提示词")
    void fileOk() throws Exception {
        when(fileUtils.getDocument(any(SysFile.class))).thenReturn(Document.from("销售额 100 万"));

        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", "https://oss.../r.xlsx", "fileName", "r.xlsx", "extension", "xlsx"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages);

        assertEquals(1, result.contents().size());
        String text = result.contents().get(0).toString();
        assertTrue(text.contains("销售额 100 万"), "文件内容要进提示词");
        assertTrue(text.contains("r.xlsx"), "文件名要进提示词");
    }

    @Test
    @DisplayName("纯文本消息：不需要 metadata")
    void textOnly() throws Exception {
        List<ChatUserMessage> messages = List.of(
                ChatUserMessage.builder().type(UserMessageType.TEXT).content("你好").metadata(null).build());

        List<Content> contents = converter.toContents(messages).contents();
        assertEquals(1, contents.size());
        assertTrue(contents.get(0).toString().contains("你好"));
    }
}
