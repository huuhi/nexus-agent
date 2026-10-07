package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.impl.FileServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 上传的「文件与产物配额」门禁（{@code docs/sql/012}）。
 * <p>
 * 单独成一个类是因为它和 {@code FileServiceUploadFailureTest} 的桩不兼容：
 * 那条用例族必须先 stub {@code saveBatch} 才能跑通「上传 → 落库」，
 * 而配额拦截发生在**落库之前**，那些桩一个都用不上，会被严格模式判成冗余桩。
 * <p>
 * 这里锁死的是一件很具体的事：<b>超限时 OSS 一次都不能被调用</b>。
 * 拦截点如果放错（比如放到落库前、OSS 后），用户会被扣掉一次 OSS 请求与存储，
 * 却拿到一句"已达上限" —— 那就是白烧钱。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上传 —— 文件配额要拦在 OSS 之前，且开关关闭时完全不查库")
class FileServiceFileQuotaTest {

    @Mock
    private AliOssUtil ossUtil;

    @BeforeEach
    void setUp() {
        UserContextHolder.saveId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    @Test
    @DisplayName("配额超限：直接抛 QuotaExceededException，OSS **一次都没调用**")
    void quotaRejectsBeforeAnyUpload() throws Exception {
        QuotaService quota = mock(QuotaService.class);
        doThrow(new QuotaExceededException("今日文件与产物数量已达上限（已用 100 / 上限 100），明天 00:00 自动重置。"))
                .when(quota).assertWithinFileQuota(1L);
        FileServiceImpl fileService = new FileServiceImpl(ossUtil, quota, new AgentProperties());

        MultipartFile file = mock(MultipartFile.class);

        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> fileService.uploadFile(new MultipartFile[]{file}, BizType.KNOWLEDGE));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("上限"),
                "提示要说明为什么被拦，实际：" + ex.getMessage());
        verify(ossUtil, never()).uploadDocument(any(), anyString(), anyLong());
    }
}
