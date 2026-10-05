package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.impl.FileServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 上传链路的失败降级（2026-10-04 修 bug 时补的回归用例）。
 * <p>
 * 背景：OSS 上传失败时，{@code FileServiceImpl} 曾经
 * <ol>
 *   <li>catch 的是 {@code com.aliyuncs.exceptions.ClientException}，而 OSS SDK 真正抛的是
 *       {@code com.aliyun.oss.ClientException / OSSException} —— 等于<b>根本没 catch 到</b>，
 *       一次 OSS 抖动就让整个批量上传接口 500；</li>
 *   <li>即使 catch 到了，{@code e.getMessage().substring(0,450)} 在 message 为 null 时
 *       直接 NPE、不足 450 字符时越界。</li>
 * </ol>
 * 这三条用例锁死「单文件失败 = 一条 FAILED 记录」这个行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上传 —— OSS 失败要降级成单条失败记录，不能整批 500")
class FileServiceUploadFailureTest {

    @Mock
    private AliOssUtil ossUtil;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = spy(new FileServiceImpl(ossUtil, mock(QuotaService.class)));
        // 绕开 MyBatis-Plus 的批量插入（需要真实 SqlSessionFactory）：本用例只关心降级逻辑
        doReturn(true).when(fileService).saveBatch(anyCollection());
        UserContextHolder.saveId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    private MultipartFile pdf(String name) throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getBytes()).thenReturn("hello".getBytes());
        when(file.getSize()).thenReturn(5L);
        return file;
    }

    @Test
    @DisplayName("OSS 抛业务异常（凭证/网络/服务端拒绝）→ 该文件标 FAILED，接口照常返回")
    void ossFailureBecomesFailedRecord() throws Exception {
        when(ossUtil.uploadDocument(any(), eq("pdf"), eq(1L)))
                .thenThrow(new ValidationException("文件上传失败（网络不通或凭证错误）：timeout"));

        List<KnowledgeFileVO> result = fileService.uploadFile(
                new MultipartFile[]{pdf("a.pdf")}, BizType.KNOWLEDGE);

        assertEquals(1, result.size());
        assertEquals(UploadStatus.FAILED, result.get(0).getUploadStatus());
        assertTrue(result.get(0).getFailReason().contains("凭证错误"),
                "失败原因要带回可读信息，实际：" + result.get(0).getFailReason());
    }

    @Test
    @DisplayName("异常 message 为 null 时不再 NPE（旧代码 e.getMessage().substring() 会炸）")
    void nullMessageDoesNotThrow() throws Exception {
        when(ossUtil.uploadDocument(any(), eq("pdf"), eq(1L)))
                .thenThrow(new ValidationException(null));

        List<KnowledgeFileVO> result = assertDoesNotThrow(() ->
                fileService.uploadFile(new MultipartFile[]{pdf("b.pdf")}, BizType.KNOWLEDGE));

        assertEquals(UploadStatus.FAILED, result.get(0).getUploadStatus());
        assertEquals("上传失败！未知原因", result.get(0).getFailReason());
    }

    @Test
    @DisplayName("超长失败原因要截断（fail_reason 列有长度约束，旧写法还会越界）")
    void longReasonIsClipped() throws Exception {
        when(ossUtil.uploadDocument(any(), eq("pdf"), eq(1L)))
                .thenThrow(new ValidationException("x".repeat(1000)));

        List<KnowledgeFileVO> result = fileService.uploadFile(
                new MultipartFile[]{pdf("c.pdf")}, BizType.KNOWLEDGE);

        // 前缀「上传失败！」+ 截断后的 450
        assertEquals("上传失败！".length() + 450, result.get(0).getFailReason().length());
    }
}
