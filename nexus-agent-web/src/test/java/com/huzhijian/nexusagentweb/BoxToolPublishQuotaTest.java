package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.exception.QuotaExceededException;
import com.huzhijian.nexusagentweb.handler.SafeExecuteToolHandler;
import com.huzhijian.nexusagentweb.sandbox.SandboxClient;
import com.huzhijian.nexusagentweb.sandbox.SandboxSessionRegistry;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.tools.BoxTool;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物发布的「文件与产物配额」门禁（{@code docs/sql/012}）。
 * <p>
 * 为什么必须测：配额的拦截点**必须在转存 OSS 之前**。
 * {@code publish_artifact} 一旦走完 {@code sandboxClient.downloadFile}，
 * 文件就已经生成并上传到 OSS 了 —— 此时再拦只能拦住"落库 + 下载卡片"，
 * 对象会变成 OSS 里的孤儿，既占存储又绕过配额。
 * 所以这里锁死的不是"有没有报错"，而是<b>"沙盒一次都没被调用"</b>。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publish_artifact —— 文件配额必须拦在转存 OSS 之前")
class BoxToolPublishQuotaTest {

    @Mock
    private SandboxClient sandboxClient;
    @Mock
    private SandboxSessionRegistry sandboxSessions;
    @Mock
    private SafeExecuteToolHandler handler;
    @Mock
    private ToolCallGuard toolCallGuard;
    @Mock
    private RunUserRegistry runUserRegistry;
    @Mock
    private QuotaService quotaService;
    @Mock
    private com.huzhijian.nexusagentweb.utils.AliOssUtil aliOssUtil;

    private BoxTool boxTool;

    @BeforeEach
    void setUp() {
        boxTool = new BoxTool(sandboxClient, sandboxSessions, handler,
                toolCallGuard, runUserRegistry, quotaService, aliOssUtil);
        // ⚠️ 必须显式 stub 成 null：Mockito 对 Map 返回值默认给**空 Map 而不是 null**，
        //    而 BoxTool 的判据是 `blocked != null` —— 不打桩就会被"空 Map"当成命中拦截直接返回。
        when(toolCallGuard.intercept(any(), anyString(), anyString())).thenReturn(null);
    }

    @Test
    @DisplayName("配额超限：返回结构化失败，且**沙盒一次都没调用**（不产生 OSS 孤儿）")
    void blockedBeforeUploadWhenQuotaExceeded() {
        when(runUserRegistry.findUserId("s1")).thenReturn(7L);
        doThrow(new QuotaExceededException("今日文件与产物数量已达上限（已用 100 / 上限 100），明天 00:00 自动重置。"))
                .when(quotaService).assertWithinFileQuota(7L);

        Map<String, Object> result = boxTool.publishArtifact("s1", "/home/report.docx", "报告.docx", "box-1");

        assertEquals(false, result.get("success"));
        assertEquals("QUOTA_EXCEEDED", result.get("errorCode"));
        assertTrue(String.valueOf(result.get("message")).contains("上限"),
                "失败信息要把原因说清楚，实际：" + result.get("message"));
        assertTrue(String.valueOf(result.get("hint")).contains("明天 00:00"),
                "要给模型一条可执行的下一步，实际：" + result.get("hint"));
        verify(sandboxClient, never()).downloadFile(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("拿不到 userId：直接失败，绝不退化成「随便发一个」（否则产物没有归属）")
    void blockedWhenUserUnknown() {
        when(runUserRegistry.findUserId("s1")).thenReturn(null);

        Map<String, Object> result = boxTool.publishArtifact("s1", "/home/report.docx", null, "box-1");

        assertEquals(false, result.get("success"));
        assertEquals("NO_USER", result.get("errorCode"));
        verify(sandboxClient, never()).downloadFile(anyString(), anyString(), any());
        verify(quotaService, never()).assertWithinFileQuota(anyLong());
    }

    @Test
    @DisplayName("未超限：正常发布，并且把**真实 userId** 传给沙盒（OSS 侧才能归属到用户）")
    void publishesWithRealUserIdWhenWithinQuota() {
        when(runUserRegistry.findUserId("s1")).thenReturn(7L);
        when(sandboxClient.downloadFile("/home/report.docx", "box-1", 7L))
                .thenReturn(Map.of("url", "https://oss/report.docx", "size", 2048,
                        // 2026-10-07：新版沙盒的版本标志，缺了会被判定为旧代码而拒绝
                        "binary_read", true));
        // mapTool 的桩要真的去执行 supplier，否则测不到"有没有走到沙盒"
        when(handler.mapTool(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Supplier<Map<String, Object>> supplier = inv.getArgument(1);
            return supplier.get();
        });

        Map<String, Object> result = boxTool.publishArtifact("s1", "/home/report.docx", "报告.docx", "box-1");

        assertEquals(true, result.get("success"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) result.get("artifact");
        assertNotNull(artifact, "成功结果必须带 artifact 结构，上层据此落库 + 推下载卡片");
        assertEquals("报告.docx", artifact.get("name"));
        assertEquals("https://oss/report.docx", artifact.get("url"));
        verify(quotaService).assertWithinFileQuota(7L);
    }
}
