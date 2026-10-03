package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import com.huzhijian.nexusagentweb.service.LexiangService;
import com.huzhijian.nexusagentweb.tools.LexiangRagTool;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LexiangRagTool 的纯单元测试。
 * <p>
 * 重点钉三件事：
 * <ol>
 *   <li>没有用户上下文时必须拒绝检索（否则是跨用户数据泄露）</li>
 *   <li>检索必须走当前用户（service 收到的 userId 必须是 {@code UserContextHolder} 里的那个）</li>
 *   <li>乐享侧失败时<b>把错误回给模型</b>而不是抛出 —— 否则整条 SSE 流会中断</li>
 * </ol>
 * 不依赖 Spring、不访问网络。
 */
class LexiangRagToolTest {

    private final LexiangService lexiangService = mock(LexiangService.class);
    private final ToolCallGuard guard = mock(ToolCallGuard.class);

    private final LexiangRagTool tool = new LexiangRagTool(lexiangService, guard);

    @AfterEach
    void clearContext() {
        UserContextHolder.removeUserId();
    }

    private LexiangApi.SearchHit hit(String title, String content, String url) {
        LexiangApi.SearchHit h = new LexiangApi.SearchHit();
        h.setTitle(title);
        h.setContent(content);
        h.setUrl(url);
        return h;
    }

    @Test
    @DisplayName("只有显式开启乐享检索时工具才启用")
    void enabledOnlyWhenRequested() {
        assertFalse(tool.enabled(ToolSelection.none()), "默认应关闭");
        assertTrue(tool.enabled(new ToolSelection(false, true)), "显式开启才启用");
        // 开着本地库不等于开着乐享库 —— 两者是独立开关
        assertFalse(tool.enabled(new ToolSelection(true, false)),
                "只开本地库时不应启用乐享检索");
    }

    @Test
    @DisplayName("没有用户上下文时拒绝检索，绝不退化为跨用户检索")
    void refusesWhenNoUserContext() {
        UserContextHolder.removeUserId();

        String result = tool.lexiangSearch("s1", "报销流程");

        assertTrue(result.contains("拒绝"), "应明确返回拒绝原因：" + result);
        verify(lexiangService, never()).search(any(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("检索必须带上当前用户 id 作为隔离维度")
    void passesCurrentUserId() {
        UserContextHolder.saveId(66L);
        when(lexiangService.search(any(), any(), anyString(), anyInt())).thenReturn(List.of());

        tool.lexiangSearch("s1", "年假");

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(lexiangService).search(captor.capture(), isNull(), eq("年假"), eq(3));
        assertEquals(66L, captor.getValue(), "必须用当前登录用户的 id 去取他的凭证");
    }

    @Test
    @DisplayName("命中结果带出标题与链接，便于模型给出可点击引用")
    void formatsHitsWithTitleAndUrl() {
        UserContextHolder.saveId(1L);
        when(lexiangService.search(any(), any(), anyString(), anyInt())).thenReturn(
                List.of(hit("员工手册", "年假需提前 3 天申请", "https://lexiangla.com/pages/abc")));

        String result = tool.lexiangSearch("s1", "年假");

        assertTrue(result.contains("员工手册"), "应带出标题：" + result);
        assertTrue(result.contains("年假需提前 3 天申请"), "应带出原文：" + result);
        assertTrue(result.contains("https://lexiangla.com/pages/abc"),
                "应带出链接以便溯源：" + result);
    }

    @Test
    @DisplayName("乐享检索失败时把原因回给模型，不抛出（避免中断整条流）")
    void failureIsReturnedNotThrown() {
        UserContextHolder.saveId(1L);
        when(lexiangService.search(any(), any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("凭证已失效，请重新保存 AppKey / AppSecret。"));

        String result = tool.lexiangSearch("s1", "年假");

        assertTrue(result.contains("检索失败"), "应说明失败：" + result);
        assertTrue(result.contains("凭证已失效"), "原始原因要透出来，前端/用户才 know 该做什么：" + result);
    }

    @Test
    @DisplayName("命中为空时提示换关键词，而不是返回空白")
    void emptyResultGivesHint() {
        UserContextHolder.saveId(1L);
        when(lexiangService.search(any(), any(), anyString(), anyInt())).thenReturn(List.of());

        String result = tool.lexiangSearch("s1", "不存在的词");

        assertTrue(result.contains("未查询到"), "应明确告知无结果：" + result);
    }

    @Test
    @DisplayName("命中被 ToolCallGuard 拦截时直接返回，不再打乐享")
    void respectsToolCallGuard() {
        UserContextHolder.saveId(1L);
        when(guard.interceptText(any(), anyString(), anyString()))
                .thenReturn("刚刚用同样的关键词检索过了，请换个说法。");

        String result = tool.lexiangSearch("s1", "年假");

        assertTrue(result.contains("换个说法"), "应直接返回拦截提示：" + result);
        verify(lexiangService, never()).search(any(), any(), anyString(), anyInt());
    }
}
