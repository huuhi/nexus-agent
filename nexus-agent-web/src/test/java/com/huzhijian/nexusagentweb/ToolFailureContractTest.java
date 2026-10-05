package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.service.SystemLogService;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.tools.LogTool;
import com.huzhijian.nexusagentweb.tools.MemoryTool;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 工具（@Tool 方法）的**返回值契约**。
 * <p>
 * 为什么单独立一个测试：工具的返回值会原样拼进模型上下文。
 * 一旦异常分支返回 {@code null} 或空串，模型看到的是"没有输出"，
 * 于是它要么瞎编、要么继续重试同一个必然失败的工具 —— 表现为用户的
 * 「AI 卡住 / 反复调用 / 答非所问」，而服务端日志里**什么都没有**。
 * <p>
 * 这类"看起来很小"的契约以前没人管：
 * <ul>
 *   <li>{@code LogTool} 原来直接 {@code return e.getMessage()}，异常无堆栈、message 为 null 时返回空串；</li>
 *   <li>{@code MemoryTool} 原来返回 {@code "错误，请勿重复" + null}，拼出"错误，请勿重复null"。</li>
 * </ul>
 * 这里用「打桩让依赖抛异常」的方式把这两条路径钉住，是纯单测覆盖不到的
 * **失败路径行为**（平时大家只测 happy path）。
 */
@DisplayName("Agent 工具失败时必须给模型可读的报错，不能返回 null/空串")
class ToolFailureContractTest {

    @Test
    @DisplayName("LogTool：保存异常时返回带原因的非空文案")
    void logToolReturnsReadableError() {
        SystemLogService logService = mock(SystemLogService.class);
        ToolCallGuard guard = mock(ToolCallGuard.class);
        doThrow(new RuntimeException("数据库连接失败")).when(logService).save(any());

        LogTool tool = new LogTool(logService, guard);
        String result = tool.recordLog("session-1", "缺少导出工具");

        assertNotNull(result, "工具返回值不能为 null，否则模型收不到任何信息");
        assertFalse(result.isBlank(), "工具返回值不能是空串");
        assertTrue(result.contains("数据库连接失败"), "要把失败原因交给模型，实际：" + result);
    }

    @Test
    @DisplayName("LogTool：异常没有 message 时退化成异常类名，不返回空串")
    void logToolFallsBackToExceptionClass() {
        SystemLogService logService = mock(SystemLogService.class);
        ToolCallGuard guard = mock(ToolCallGuard.class);
//        RuntimeException() 无参构造的 getMessage() 就是 null
        doThrow(new RuntimeException()).when(logService).save(any());

        String result = new LogTool(logService, guard).recordLog("session-1", "缺少导出工具");

        assertNotNull(result);
        assertFalse(result.isBlank(), "message 为 null 时也不能返回空串");
        assertTrue(result.contains("RuntimeException"), "应回退为异常类名，实际：" + result);
    }

    @Test
    @DisplayName("MemoryTool：检索异常时不拼出 null，且带上失败原因")
    void memoryToolReturnsReadableError() {
        UserMemoryService memoryService = mock(UserMemoryService.class);
        ToolCallGuard guard = mock(ToolCallGuard.class);
        RunUserRegistry runUserRegistry = mock(RunUserRegistry.class);
        // 2026-10-05 起工具走 RunUserRegistry 反查 userId（工具线程上 UserContextHolder 恒为 null）
        when(runUserRegistry.findUserId(any())).thenReturn(7L);
        when(memoryService.getMemory(anyLong(), anyString()))
                .thenThrow(new RuntimeException("pg_trgm 不可用"));

        MemoryTool tool = new MemoryTool(memoryService, guard, runUserRegistry);
        String result = tool.searchUserMemory("session-1", "饮食偏好");

        assertNotNull(result);
        assertFalse(result.isBlank());
        assertFalse(result.contains("null"), "不能把 null 拼进给模型的文案，实际：" + result);
        assertTrue(result.contains("pg_trgm 不可用"), "要带上失败原因，实际：" + result);
    }

    @Test
    @DisplayName("MemoryTool：拿不到 userId 时**明确报错**，绝不退化成写一条 user_id 为空的脏数据")
    void memoryToolRefusesToSaveWithoutUser() {
        UserMemoryService memoryService = mock(UserMemoryService.class);
        ToolCallGuard guard = mock(ToolCallGuard.class);
        RunUserRegistry runUserRegistry = mock(RunUserRegistry.class);
        when(runUserRegistry.findUserId(any())).thenReturn(null);

        String result = new MemoryTool(memoryService, guard, runUserRegistry)
                .saveLongMemory("session-1", "用户喜欢看电影", null);

        assertNotNull(result);
        assertTrue(result.contains("未保存"), "必须让模型知道这次没存上，实际：" + result);
        // 关键：一次都没写库 —— 否则就是 2026-10-05 那个「接口说 ok、库里啥也没有」的故障
        verify(memoryService, never()).saveMemory(any());
    }
}
