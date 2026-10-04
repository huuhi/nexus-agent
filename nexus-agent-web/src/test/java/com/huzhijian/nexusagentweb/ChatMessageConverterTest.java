package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.ChatUserMessage;
import com.huzhijian.nexusagentweb.em.UserMessageType;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AliOssProperties;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import com.huzhijian.nexusagentweb.utils.OssUrlGuard;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatMessageConverter} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 线上那个 NPE：带图片 / 带文档聊天直接 500，
 * 日志只有 {@code Cannot invoke "Object.toString()" because the return value of "Map.get(Object)" is null}，
 * 看不出是哪个字段没传。根因是前端文档里把字段名写成了 {@code url}，
 * 而后端要的是 {@code fileUrl}（驼峰），于是 {@code metadata.get("fileUrl")} 恒为 null。
 * <p>
 * <b>2026-10-04 新增</b>：附件归属校验（{@link OssUrlGuard}）相关用例。
 * 这里用真实的 {@link OssUrlGuard} 而不是 mock —— 归属判定是一堆字符串前缀/域名比较，
 * mock 掉就等于没测；而它本身不碰网络，构造起来只要一个 {@link AliOssProperties}。
 */
@DisplayName("ChatMessageConverter —— 附件消息缺字段给明确报错 + 附件归属校验")
class ChatMessageConverterTest {

    private static final Long USER_ID = 1001L;
    /** 与 application.yml 里的 spring.aliyun 保持一致 */
    private static final String OSS_HOST = "nexus-agent-file.oss-cn-guangzhou.aliyuncs.com";
    private static final String OWN_FILE =
            "https://" + OSS_HOST + "/user/file/user_1001/2026/10/04/abc.xlsx";
    private static final String OTHER_USER_FILE =
            "https://" + OSS_HOST + "/user/file/user_1002/2026/10/04/secret.pdf";
    private static final String OWN_IMAGE =
            "https://" + OSS_HOST + "/user/avatar/1a2b3c.png";

    private FileUtils fileUtils;
    private ChatMessageConverter converter;

    @BeforeEach
    void setUp() {
        fileUtils = mock(FileUtils.class);

        AliOssProperties props = new AliOssProperties();
        props.setEndpoint("https://oss-cn-guangzhou.aliyuncs.com");
        props.setBucketName("nexus-agent-file");
        props.setFileDir("user/file");
        props.setImageDir("user/avatar");
        // 真实的 guard：归属判定全靠它，mock 掉就等于没测
        OssUrlGuard guard = new OssUrlGuard(props);

        converter = new ChatMessageConverter(fileUtils, guard);
    }

    @Test
    @DisplayName("图片消息没传 metadata：报 400 并说明必需字段，不能 NPE")
    void imageWithoutMetadata() {
        List<ChatUserMessage> messages = List.of(
                ChatUserMessage.builder().type(UserMessageType.IMAGE).content("").metadata(null).build());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> converter.toContents(messages, true, USER_ID));
        assertTrue(ex.getMessage().contains("fileUrl"), "报错里要说清期望的字段名：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("图片消息 metadata 里写成了 url（不是 fileUrl）：报 400 并列出缺哪个字段")
    void imageWithWrongKey() {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("url", OWN_IMAGE))
                .build());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> converter.toContents(messages, true, USER_ID));
        assertTrue(ex.getMessage().contains("fileUrl"), "报错里要指出缺的是 fileUrl：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("文档缺 extension：从 fileUrl 推断（少一次前后端往返）")
    void fileWithoutExtensionInfersFromUrl() throws Exception {
        when(fileUtils.getDocument(any(SysFile.class), eq(USER_ID))).thenReturn(Document.from("内容"));

        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", OWN_FILE + "?x-oss-signature=abc"))
                .build());

//        不抛异常即说明推断成功（URL 的 query 部分要能被剥掉）
        assertEquals(1, converter.toContents(messages, true, USER_ID).contents().size());
    }

    @Test
    @DisplayName("文档既没 extension、fileUrl 里也没有：报 400 说清原因")
    void fileWithoutAnyExtension() {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", "https://" + OSS_HOST + "/user/file/user_1001/noext"))
                .build());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> converter.toContents(messages, true, USER_ID));
        assertTrue(ex.getMessage().contains("extension"), "报错里要指出需要 extension：实际是 " + ex.getMessage());
    }

    @Test
    @DisplayName("正常图片：产生真正的 ImageContent（多模态模型才能看到图），attached_files 保留原始 metadata")
    void imageOk() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("fileUrl", OWN_IMAGE, "fileName", "a.png"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages, true, USER_ID);

        assertEquals(1, result.contents().size());
//        2026-10-03：必须是 ImageContent —— URL 文本模型是"看不到"的
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                dev.langchain4j.data.message.ImageContent.class, result.contents().get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attached =
                (List<Map<String, Object>>) result.metadata().get("attached_files");
        assertEquals(1, attached.size());
        assertEquals(OWN_IMAGE, attached.get(0).get("fileUrl"));
    }

    @Test
    @DisplayName("模型不支持视觉（vision=false）：图片降级为 URL 文本，不能发 ImageContent")
    void imageDegradesWhenModelHasNoVision() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("fileUrl", OWN_IMAGE, "fileName", "a.png"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages, false, USER_ID);

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
    @DisplayName("正常文档：调用解析器并把正文拼进提示词，且把 userId 传下去做归属校验")
    void fileOk() throws Exception {
        when(fileUtils.getDocument(any(SysFile.class), eq(USER_ID)))
                .thenReturn(Document.from("销售额 100 万"));

        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", OWN_FILE, "fileName", "r.xlsx", "extension", "xlsx"))
                .build());

        ChatMessageConverter.ConvertedMessage result = converter.toContents(messages, true, USER_ID);

        assertEquals(1, result.contents().size());
        String text = result.contents().get(0).toString();
        assertTrue(text.contains("销售额 100 万"), "文件内容要进提示词");
        assertTrue(text.contains("r.xlsx"), "文件名要进提示词");
//        关键回归：userId 必须一路传到底，否则归属校验形同虚设
        verify(fileUtils).getDocument(any(SysFile.class), eq(USER_ID));
    }

    @Test
    @DisplayName("P1 越权：文档地址指向别人的目录 → 在解析器被调用之前就拒绝")
    void fileBelongingToAnotherUserIsRejected() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", OTHER_USER_FILE, "fileName", "secret.pdf", "extension", "pdf"))
                .build());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> converter.toContents(messages, true, USER_ID));
        assertTrue(ex.getMessage().contains("不属于"), "报错要说清是归属问题：实际是 " + ex.getMessage());
//        最关键的一条断言：解析器根本没被调用
//        （如果先解析再校验，等于"读都读完了才说不给"）
        verify(fileUtils, never()).getDocument(any(SysFile.class), anyLong());
    }

    @Test
    @DisplayName("P1 SSRF：文档地址是任意外站 → 拒绝，不去取")
    void fileFromForeignHostIsRejected() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl", "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                        "fileName", "x", "extension", "pdf"))
                .build());

        assertThrows(ValidationException.class, () -> converter.toContents(messages, true, USER_ID));
        verify(fileUtils, never()).getDocument(any(SysFile.class), anyLong());
    }

    @Test
    @DisplayName("P1 SSRF：图片地址是任意外站 → 拒绝（否则等于让模型替我去访问任意 URL）")
    void imageFromForeignHostIsRejected() {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.IMAGE)
                .content("")
                .metadata(Map.of("fileUrl", "http://evil.example.com/tracker.png"))
                .build());

        assertThrows(ValidationException.class, () -> converter.toContents(messages, true, USER_ID));
    }

    @Test
    @DisplayName("同前缀不同人：user_1001_evil 这类构造不能被当成 user_1001 的文件")
    void prefixConfusionIsRejected() throws Exception {
        List<ChatUserMessage> messages = List.of(ChatUserMessage.builder()
                .type(UserMessageType.FILE)
                .content("")
                .metadata(Map.of("fileUrl",
                        "https://" + OSS_HOST + "/user/file/user_1001_evil/2026/10/04/x.xlsx",
                        "fileName", "x.xlsx", "extension", "xlsx"))
                .build());

        assertThrows(ValidationException.class, () -> converter.toContents(messages, true, USER_ID));
        verify(fileUtils, never()).getDocument(any(SysFile.class), anyLong());
    }

    @Test
    @DisplayName("纯文本消息：不需要 metadata")
    void textOnly() throws Exception {
        List<ChatUserMessage> messages = List.of(
                ChatUserMessage.builder().type(UserMessageType.TEXT).content("你好").metadata(null).build());

        List<Content> contents = converter.toContents(messages, true, USER_ID).contents();
        assertEquals(1, contents.size());
        assertTrue(contents.get(0).toString().contains("你好"));
    }
}
