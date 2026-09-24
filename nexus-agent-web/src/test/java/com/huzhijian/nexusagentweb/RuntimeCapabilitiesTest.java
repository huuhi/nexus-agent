package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.service.impl.ChatServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「运行时能力说明」组装的单元测试（P2-9）。
 * <p>
 * 这段文本是**直接给模型看的**：措辞决定了模型会不会去调用一个连不上的服务、
 * 会不会把"配了但连不上"说成"我没有这个能力"。所以它的结构值得被测试固定下来。
 * <p>
 * 注：{@code McpInformationServiceImpl.getMcp} 里"收集不可用服务名"的那段依赖
 * MyBatis-Plus 的链式查询 API，不易在不连库的情况下测；且本机库里没有 MCP 配置可端到端验证。
 */
class RuntimeCapabilitiesTest {

    @Test
    @DisplayName("没有 MCP 不可用项时，只输出技能段")
    void onlySkillsWhenNoMcpProblem() {
        String text = ChatServiceImpl.composeCapabilities("my-skill：处理 PDF", List.of());

        assertTrue(text.contains("【可用技能】"), text);
        assertTrue(text.contains("my-skill：处理 PDF"), text);
        assertFalse(text.contains("MCP"), "不应凭空出现 MCP 段：" + text);
    }

    @Test
    @DisplayName("有 MCP 不可用项时，列出服务名并明确要求不要调用")
    void listsUnavailableMcpServices() {
        String text = ChatServiceImpl.composeCapabilities("（无技能）", List.of("fetch-server", "github-mcp"));

        assertTrue(text.contains("【MCP 能力状态】"), text);
        assertTrue(text.contains("fetch-server"), text);
        assertTrue(text.contains("github-mcp"), text);
        assertTrue(text.contains("不要尝试调用"), text);
    }

    @Test
    @DisplayName("技能清单为空时给出明确说明，而不是留空占位")
    void blankSkillsGetExplicitNote() {
        String textNull = ChatServiceImpl.composeCapabilities(null, List.of());
        String textBlank = ChatServiceImpl.composeCapabilities("   ", List.of());

        assertTrue(textNull.contains("当前没有可用的技能"), textNull);
        assertTrue(textBlank.contains("当前没有可用的技能"), textBlank);
    }

    @Test
    @DisplayName("不可用清单为 null 时不抛异常（调用方可能没传）")
    void nullUnavailableListIsSafe() {
        String text = ChatServiceImpl.composeCapabilities("skill-a：测试", null);

        assertTrue(text.contains("skill-a：测试"), text);
        assertFalse(text.contains("MCP"), text);
    }

    @Test
    @DisplayName("技能与 MCP 状态同时存在时两段都在（顺序：技能在前）")
    void bothSectionsCoexist() {
        String text = ChatServiceImpl.composeCapabilities("skill-a：测试", List.of("broken-mcp"));

        int skillsAt = text.indexOf("【可用技能】");
        int mcpAt = text.indexOf("【MCP 能力状态】");
        assertTrue(skillsAt >= 0 && mcpAt > skillsAt, "MCP 段应在技能段之后：" + text);
    }
}
