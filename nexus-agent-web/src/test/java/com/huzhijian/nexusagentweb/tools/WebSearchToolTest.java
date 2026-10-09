package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 联网搜索工具的单元测试（2026-10-07 新增）。
 * <p>
 * 只测不需要出网的部分：启用判定、入参卫生、响应解析。
 * HTTP 调用本身依赖外部服务，交给人工冒烟 —— 硬 mock 它只会测到自己的桩。
 */
@DisplayName("web_search —— 未配 Key 不注册，解析健壮，失败可读")
class WebSearchToolTest {

    private WebSearchTool tool(String envKey) {
        return new WebSearchTool(new AgentProperties(), mock(ToolCallGuard.class),
                new ToolSourceStore(), envKey);
    }

    @Test
    @DisplayName("🔴 未配置 TAVILY_API_KEY → 整个工具集不注册（模型看不到，而不是调用了才报错）")
    void notEnabledWithoutKey() {
        assertFalse(tool(null).enabled(null));
        assertFalse(tool("  ").enabled(null));
    }

    @Test
    @DisplayName("配置了 Key 且总开关开 → 启用；总开关关 → 禁用")
    void enabledFlagRespected() {
        assertTrue(tool("tvly-test-key").enabled(null));

        AgentProperties props = new AgentProperties();
        props.getWebsearch().setEnabled(false);
        assertFalse(new WebSearchTool(props, mock(ToolCallGuard.class), new ToolSourceStore(), "tvly-test-key").enabled(null),
                "总开关应能强行关闭");
    }

    @Test
    @DisplayName("空关键词直接拒绝，不发出网络请求")
    void blankQueryRejected() {
        WebSearchTool t = tool("tvly-test-key");
        String result = t.webSearch("s1", "   ");
        assertTrue(result.startsWith("error:"), () -> "实际：" + result);
    }

    @Test
    @DisplayName("解析 Tavily 响应：title/url/content 格式化成模型易读的列表")
    void parsesTavilyResponse() {
        String body = """
                {"results":[
                  {"title":"2026年高考报名人数","url":"https://example.com/a","content":"共 1300 万人"},
                  {"title":"第二条第","url":"https://example.com/b","content":"..."}
                ]}
                """;
        String out = WebSearchTool.formatResults(body, "2026 高考人数");
        assertTrue(out.startsWith("1. "), () -> "实际：" + out);
        assertTrue(out.contains("https://example.com/a"));
        assertTrue(out.contains("1300 万"), "content 摘要要带出来，模型要靠它回答");
        assertTrue(out.contains("2. "), "两条都要在");
    }

    @Test
    @DisplayName("空结果要明确说「没有找到」，绝不能返回空串（模型无法区分没结果和工具坏了）")
    void emptyResultsSaySo() {
        String out = WebSearchTool.formatResults("{\"results\":[]}", "冷门查询");
        assertTrue(out.contains("没有搜索到"), () -> "实际：" + out);
    }

    @Test
    @DisplayName("响应解析失败也按可读错误返回，不炸对话")
    void brokenJsonIsTolerated() {
        String out = WebSearchTool.formatResults("not-json{", "q");
        assertTrue(out.startsWith("error:"), () -> "实际：" + out);
    }

    // ==================== 结构化来源（给前端「来源卡片」用的那份数据）====================

    private static final String TWO_RESULTS = """
            {"results":[
              {"title":"2026年高考报名人数","url":"https://example.com/a","content":"共 1300 万人，创历史新高。"},
              {"title":"第二条标题","url":"https://example.com/b","content":"另一段摘要。"}
            ]}
            """;

    @Test
    @DisplayName("抽出 index/title/url/snippet，且编号与给模型的文本严格对齐（模型写 [2] → 前端取第 2 条）")
    void extractsStructuredSourcesAlignedWithModelText() {
        List<Map<String, Object>> sources = WebSearchTool.sources(TWO_RESULTS);

        assertEquals(2, sources.size());
        assertEquals(1, sources.get(0).get("index"));
        assertEquals("2026年高考报名人数", sources.get(0).get("title"));
        assertEquals("https://example.com/a", sources.get(0).get("url"));
        assertTrue(String.valueOf(sources.get(0).get("snippet")).contains("1300 万"));

//        对齐校验：同一份响应里，格式为也会把第一条编成 1，第二条编成 2
        String text = WebSearchTool.formatResults(TWO_RESULTS, "高考人数");
        assertTrue(text.startsWith("1. 2026年高考报名人数"), () -> "实际：" + text);
        assertTrue(text.contains("2. 第二条标题"), () -> "实际：" + text);

//        🔴 index 必须是 Integer：全局 JacksonConfig 会把 Long/long 序列化成字符串
//        （雪花 ID 精度），写成 Long 前端就会收到 "1" —— 与 ttfbMs 那个坑完全同源
        assertInstanceOf(Integer.class, sources.get(0).get("index"),
                "index 必须是 int，Long 下发后会变成字符串");
    }

    @Test
    @DisplayName("摘要超长要截断（这是 SSE 帧载荷，不是模型上下文，没必要搬全文）")
    void snippetIsClipped() {
        String body = "{\"results\":[{\"title\":\"t\",\"url\":\"https://x.com\",\"content\":\""
                + "很长".repeat(200) + "\"}]}";
        List<Map<String, Object>> sources = WebSearchTool.sources(body);
        assertTrue(String.valueOf(sources.get(0).get("snippet")).length() <= 300,
                "摘要没有截断，SSE 帧会膨胀：实际长度 "
                        + String.valueOf(sources.get(0).get("snippet")).length());
    }

    @Test
    @DisplayName("脏响应返回空列表而不是抛异常；也不能影响给模型的那条文本")
    void malformedResponseYieldsEmptySources() {
        assertEquals(0, WebSearchTool.sources("{\"results\":[]}").size());
        assertEquals(0, WebSearchTool.sources("not-json{").size());
//        反向验证：正常响应必须是非空的，否则上面两条是恒真断言
        assertTrue(WebSearchTool.sources(TWO_RESULTS).size() > 0);
    }
}
