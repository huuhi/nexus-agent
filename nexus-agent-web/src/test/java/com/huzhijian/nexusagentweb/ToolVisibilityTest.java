package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具可见性判定（2026-10-07）。
 * <p>
 * <b>为什么值得单独测</b>：这是一份「看不见的配置」—— 配错了的表现不是报错，
 * 而是"用户又看到一堆内部工具卡片"或"该看到的也看不到了"，两种都很难自查。
 * 所以默认清单必须钉死，且要有反向验证证明是清单在驱动行为（而不是代码里写死的 if）。
 */
@DisplayName("工具可见性 —— 内部工具对前端隐藏，功能性工具照常显示")
class ToolVisibilityTest {

    private ToolVisibility visibility() {
        return new ToolVisibility(new AgentProperties());
    }

    @Test
    @DisplayName("默认隐藏「内部基建类」：建/销毁沙盒、读写长期记忆、记日志")
    void internalToolsAreHiddenByDefault() {
        ToolVisibility v = visibility();
        for (String name : List.of("create_box", "delete_box", "search_user_memory",
                "save_user_data", "record_log")) {
            assertTrue(v.isHidden(name), "内部工具 [" + name + "] 应当对前端隐藏");
        }
    }

    @Test
    @DisplayName("默认可见「功能性」工具：执行代码 / 执行命令 / 发布产物 / 知识库检索 / 文件读写")
    void functionalToolsStayVisible() {
        ToolVisibility v = visibility();
        for (String name : List.of("execute_code", "execute_cmd", "publish_artifact",
                "lexiang_search", "create_write_file", "list_dir", "download_file")) {
            assertFalse(v.isHidden(name), "功能性工具 [" + name + "] 应当照常显示给用户");
        }
    }

    @Test
    @DisplayName("工具名大小写与空格容错（模型偶尔会带空格，yml 里也可能写成大写）")
    void nameMatchingIsTolerant() {
        ToolVisibility v = visibility();
        assertTrue(v.isHidden("  Create_Box  "));
        assertTrue(v.isHidden("CREATE_BOX"));
        assertFalse(v.isHidden(" execute_code "));
    }

    @Test
    @DisplayName("工具名为 null / 空白时一律放行 —— 拿不到名字就显示，绝不吞消息")
    void unknownNameIsVisible() {
        ToolVisibility v = visibility();
        assertFalse(v.isHidden(null));
        assertFalse(v.isHidden(""));
        assertFalse(v.isHidden("   "));
    }

    @Test
    @DisplayName("反向验证：清空配置后 create_box 重新可见（证明是配置在驱动，不是写死的 if）")
    void emptyConfigShowsEverything() {
        AgentProperties props = new AgentProperties();
        props.getTools().getHiddenTools().clear();
        ToolVisibility v = new ToolVisibility(props);
        assertFalse(v.isHidden("create_box"),
                "配置已清空，create_box 却仍被隐藏 —— 说明判定被写死在代码里了");
        assertTrue(v.hiddenTools().isEmpty());
    }

    @Test
    @DisplayName("自定义清单生效：只隐藏 execute_code 时，其余工具不受影响")
    void customListOverridesDefault() {
        AgentProperties props = new AgentProperties();
        props.getTools().getHiddenTools().clear();
        props.getTools().getHiddenTools().add("execute_code");
        ToolVisibility v = new ToolVisibility(props);
        assertTrue(v.isHidden("execute_code"));
        assertFalse(v.isHidden("create_box"),
                "自定义清单应整份取代默认清单，而不是叠加");
    }
}
