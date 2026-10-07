package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「隐藏工具清单」的单一事实源护栏（2026-10-07 新增字段时补的，同 {@code SseTimeoutDriftTest} 思路）。
 * <p>
 * <b>为什么要这条护栏</b>：{@code nexus.agent.tools.hidden-tools} 的默认清单写在
 * {@link AgentProperties.Tools#getHiddenTools()} 里，而 profile 属性源的优先级
 * <b>高于</b>代码默认值 —— 一旦有人在 {@code application-prod.yml} 里写了这一行，
 * 默认清单就被<b>整份</b>盖掉（不是合并），而代码那边看起来毫无变化。
 * 本项目这个坑已经炸过两次（{@code sse.timeout}、{@code memory.max-tokens}），
 * 两次都逃过了当时的护栏，所以新字段一律自带同款护栏。
 * <p>
 * 钉死的不变式：<b>唯一事实源是 AgentProperties 的默认值，任何 profile yml 都不得声明这个键。</b>
 */
@DisplayName("隐藏工具清单单一事实源（防 profile 配置整份盖掉代码默认值）")
class ToolVisibilityDriftTest {

    /** surefire 的工作目录是模块目录，向上找到仓库根（有 .env.example 的那一层） */
    private static Path repoRoot() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IOException("找不到仓库根目录（向上 5 层都没有 .env.example）");
    }

    @Test
    @DisplayName("默认清单非空，且包含五个内部基建工具")
    void defaultListIsComplete() {
        List<String> hidden = new AgentProperties().getTools().getHiddenTools();
        assertNotNull(hidden, "默认清单不应为 null");
        for (String name : List.of("create_box", "delete_box", "search_user_memory",
                "save_user_data", "record_log")) {
            assertTrue(hidden.contains(name),
                    "默认隐藏清单缺少 [" + name + "] —— 它会重新出现在用户面前");
        }
    }

    @Test
    @DisplayName("任何 yml（主配置 / prod / dev 模板 / 部署覆盖文件）都不得声明 nexus.agent.tools.hidden-tools")
    void noYmlOverridesHiddenTools() throws IOException {
        List<String> files = List.of(
                "nexus-agent-web/src/main/resources/application.yml",
                "nexus-agent-web/src/main/resources/application-prod.yml",
                "nexus-agent-web/src/main/resources/application-dev.yml.example",
                "conf/nexus-override.yml");
        for (String rel : files) {
            Path file = repoRoot().resolve(rel);
            if (!Files.exists(file)) {
                continue;
            }
            Object tools = dig(new Yaml().load(Files.readString(file)), "nexus", "agent", "tools");
            if (!(tools instanceof Map<?, ?> toolsMap)) {
                continue; // 没有 tools 段 = 走代码默认，正是我们想要的形态
            }
            assertFalse(toolsMap.containsKey("hidden-tools"),
                    rel + " 里声明了 nexus.agent.tools.hidden-tools=" + toolsMap.get("hidden-tools")
                            + " —— 它会**整份取代** AgentProperties 里的默认清单（不是合并），"
                            + "结果就是「以为只改了一行，其实其它工具全变回可见」。"
                            + "要调整清单，改 AgentProperties.Tools.hiddenTools 的默认值，全项目只此一处；"
                            + "确需按环境覆盖时也请写完整清单并在此处登记例外。");
        }
    }

    /** 逐层下钻取嵌套键，任何一层缺失返回 null */
    private static Object dig(Object root, String... keys) {
        Object cur = root;
        for (String key : keys) {
            if (!(cur instanceof Map<?, ?> map) || !map.containsKey(key)) {
                return null;
            }
            cur = map.get(key);
        }
        return cur;
    }
}
