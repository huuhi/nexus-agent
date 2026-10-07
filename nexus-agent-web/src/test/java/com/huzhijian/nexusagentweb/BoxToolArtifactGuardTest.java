package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.handler.SafeExecuteToolHandler;
import com.huzhijian.nexusagentweb.sandbox.SandboxClient;
import com.huzhijian.nexusagentweb.sandbox.SandboxSessionRegistry;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.tools.BoxTool;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物发布的**两道防线**（2026-10-07，用户报「png/jpg/docx 全部打不开、svg/md/html/csv 正常」后补）。
 * <p>
 * 这个症状是「二进制被按文本处理」的精确特征：文本文件没有非法字节，怎么解码都不坏；
 * 二进制文件任何字节被替换都致命。10-05 修过一次（E2B SDK format 默认 "text"），
 * 但修复在**沙盒侧** —— 线上沙盒跑旧代码时 Java 侧完全无从察觉（响应形状一模一样），
 * 于是 10-07 又复发了。
 * <p>
 * 两道防线，都必须有测试钉死：
 * <ol>
 *   <li><b>版本标志</b>：新版沙盒响应带 {@code binary_read: true}，缺它 = 沙盒代码过旧 →
 *       明确报 {@code SANDBOX_CODE_OUTDATED} 并给出部署指引；</li>
 *   <li><b>魔数校验</b>：发布前读 OSS 对象头，与扩展名的魔数比对，不匹配 →
 *       拒绝发布并删掉坏文件。这层能抓住**任何来源**的字节损坏。</li>
 * </ol>
 */
@DisplayName("publish_artifact 两道防线 —— 沙盒旧代码要报错，坏文件不能流到用户手里")
class BoxToolArtifactGuardTest {

    private SandboxClient sandboxClient;
    private AliOssUtil aliOssUtil;
    private BoxTool boxTool;

    @BeforeEach
    void setUp() {
        sandboxClient = mock(SandboxClient.class);
        aliOssUtil = mock(AliOssUtil.class);
        SafeExecuteToolHandler handler = mock(SafeExecuteToolHandler.class);
//        mapTool 桩要真的执行 supplier，否则测不到沙盒调用
        doAnswer(inv -> ((Supplier<Map<String, Object>>) inv.getArgument(1)).get())
                .when(handler).mapTool(anyString(), any());
        ToolCallGuard guard = mock(ToolCallGuard.class);
        org.mockito.Mockito.when(guard.intercept(any(), anyString(), anyString())).thenReturn(null);
        RunUserRegistry runUserRegistry = mock(RunUserRegistry.class);
        org.mockito.Mockito.when(runUserRegistry.findUserId(any())).thenReturn(7L);

        boxTool = new BoxTool(sandboxClient, mock(SandboxSessionRegistry.class), handler,
                guard, runUserRegistry, mock(QuotaService.class), aliOssUtil);
    }

    private void sandboxReturns(String url) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("url", url);
        resp.put("size", 2048);
        resp.put("binary_read", true); // 新版沙盒的标志
        when(sandboxClient.downloadFile(eq("/home/report.png"), eq("box-1"), eq(7L))).thenReturn(resp);
    }

    private Map<String, Object> publish() {
        return boxTool.publishArtifact("s1", "/home/report.png", "report.png", "box-1");
    }

    @Test
    @DisplayName("🔴 沙盒响应缺 binary_read 标志 → 判定沙盒代码过旧，明确报错并给部署指引")
    void outdatedSandboxIsRejected() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("url", "https://oss/report.png");
        resp.put("size", 2048); // 旧代码：没有 binary_read 字段
        when(sandboxClient.downloadFile(eq("/home/report.png"), eq("box-1"), eq(7L))).thenReturn(resp);

        Map<String, Object> result = publish();

        assertEquals("SANDBOX_CODE_OUTDATED", result.get("errorCode"),
                "旧沙盒必须被明确拒绝 —— 静默放行的结果是用户拿到打不开的 png（10-07 线上复现）");
        String hint = String.valueOf(result.get("hint"));
        assertTrue(hint.contains("nexus_agent_box"), "指引要说到点子上（重新部署沙盒代码）：实际=" + hint);
    }

    @Test
    @DisplayName("魔数不匹配（png 头被换成 U+FFFD）→ 拒绝发布并删掉坏文件")
    void corruptedPngIsRejectedAndDeleted() {
        sandboxReturns("https://oss/report.png");
//        模拟被文本损坏的字节：U+FFFD 的 UTF-8 编码 EF BF BD 开头
        when(aliOssUtil.readObjectHead(eq("https://oss/report.png"), anyInt()))
                .thenReturn(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD, 0x50, 0x4E, 0x47, 0x0D, 0x0A});

        Map<String, Object> result = publish();

        assertEquals("SANDBOX_ARTIFACT_CORRUPTED", result.get("errorCode"),
                "坏文件必须拦在发布之前 —— 用户拿到打不开的产物比明确报错糟得多");
        verify(aliOssUtil).deleteByUrl("https://oss/report.png");
    }

    @Test
    @DisplayName("魔数匹配 → 正常发布")
    void healthyPngIsPublished() {
        sandboxReturns("https://oss/report.png");
        when(aliOssUtil.readObjectHead(eq("https://oss/report.png"), anyInt()))
                .thenReturn(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        Map<String, Object> result = publish();

        assertEquals(true, result.get("success"), () -> "实际：" + result);
    }

    @Test
    @DisplayName("文本类扩展名（csv/md/html/svg）不校验魔数 —— 它们没有魔数，误校验必炸")
    void textExtensionsSkipMagicCheck() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("url", "https://oss/data.csv");
        resp.put("size", 100);
        resp.put("binary_read", true);
        when(sandboxClient.downloadFile(eq("/home/data.csv"), eq("box-1"), eq(7L))).thenReturn(resp);

        Map<String, Object> result = boxTool.publishArtifact("s1", "/home/data.csv", "data.csv", "box-1");

        assertEquals(true, result.get("success"), () -> "实际：" + result);
        verify(aliOssUtil, org.mockito.Mockito.never()).readObjectHead(anyString(), anyInt());
    }

    @Test
    @DisplayName("读不到对象头（OSS 抖动）→ 放行而不是拦截（发布链路自己会暴露下载问题）")
    void unreadableHeadPassesThrough() {
        sandboxReturns("https://oss/report.png");
        when(aliOssUtil.readObjectHead(anyString(), anyInt())).thenReturn(null);

        Map<String, Object> result = publish();

        assertEquals(true, result.get("success"), () -> "实际：" + result);
    }

    @Test
    @DisplayName("超长编码头也不行：docx 是 zip 容器，PK 魔数必须对上")
    void corruptedDocxIsRejected() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("url", "https://oss/report.docx");
        resp.put("size", 5000);
        resp.put("binary_read", true);
        when(sandboxClient.downloadFile(eq("/home/report.docx"), eq("box-1"), eq(7L))).thenReturn(resp);
        when(aliOssUtil.readObjectHead(eq("https://oss/report.docx"), anyInt()))
                .thenReturn(new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD, 0x00, 0x00, 0x00, 0x00, 0x00});

        Map<String, Object> result = boxTool.publishArtifact("s1", "/home/report.docx", "report.docx", "box-1");

        assertEquals("SANDBOX_ARTIFACT_CORRUPTED", result.get("errorCode"));
        verify(aliOssUtil).deleteByUrl("https://oss/report.docx");
    }
}
