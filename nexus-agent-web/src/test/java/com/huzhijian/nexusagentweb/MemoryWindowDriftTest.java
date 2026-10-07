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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-06 晚间「1M 上下文的模型，聊几次就被提示新建会话」的防复发护栏。
 * <p>
 * <b>线上现象</b>：用户反馈「现在很多模型都是 1M，我对话几次就让 new 窗口了，怎么可能那么快」。
 * <b>根因</b>：{@code application-prod.yml} 里残留 {@code nexus.agent.memory.max-tokens: 24000}，
 * 而 {@code AgentProperties.Memory.maxTokens} 的<b>默认值当天已经修正回 300000</b>。
 * profile 属性源优先级高于代码默认值，用户又以 prod profile 运行 ——
 * 于是 1M 上下文的模型实际只拿到 2.4 万 token 记忆窗口（≈ 声明能力的 2.4%）。
 * <p>
 * <b>为什么既有护栏全绿却没拦住</b>：{@code ProdLatencyGuardTest} 当时用
 * {@code new AgentProperties()} 的<b>代码默认值</b>去算生效窗口，从来不读 prod 里写了什么。
 * 「读配置文件的测试却读默认值」，等于护栏测了个寂寞。
 * 这是 2026-10-05「prod 残留 sse.timeout: 120s」的<b>同构复发</b> ——
 * 那次留下了 {@code SseTimeoutDriftTest}，这次补上记忆窗口这一侧。
 * <p>
 * 钉死的不变式：<b>记忆窗口的唯一事实源是 {@code AgentProperties.Memory.maxTokens}，
 * 任何 profile yml 都不许声明 {@code nexus.agent.memory.max-tokens}。</b>
 */
@DisplayName("记忆窗口单一事实源（防 profile 配置盖掉代码默认值）")
class MemoryWindowDriftTest {

    /**
     * 至少要装得下这么多 token。低于它，长会话会被<b>静默</b>裁掉更早的历史 ——
     * 用户只会看到「建议新建对话」的提示，然后以为是自己聊得太多。
     * 2026-10-06 那次生效值只有 24000，就是这条被击穿。
     */
    private static final int MIN_USEFUL_WINDOW = 80_000;

    /** 上限：防止误填 10M 之类把每轮 prefill 拉到几十秒 */
    private static final int MAX_MEMORY_WINDOW = 400_000;

    /** 需要检查「不许声明该键」的配置文件 */
    private static final List<String> YML_FILES = List.of(
            "nexus-agent-web/src/main/resources/application.yml",
            "nexus-agent-web/src/main/resources/application-prod.yml",
            "nexus-agent-web/src/main/resources/application-dev.yml.example",
            "conf/nexus-override.yml");

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
    @DisplayName("代码默认窗口要够大：至少装得下一个正常会话，也不能大到没有上限")
    void codeDefaultWindowStaysReasonable() {
        int window = new AgentProperties().getMemory().getMaxTokens();
        assertTrue(window >= MIN_USEFUL_WINDOW,
                "nexus.agent.memory.max-tokens 的默认值只有 " + window + " token，小于 " + MIN_USEFUL_WINDOW + "。"
                        + "1M 上下文的模型会被这个值砍掉九成以上的能力 —— 用户表现为"
                        + "「聊没几轮就开始忘事，还被提示新建会话」。2026-10-06 的 24000 正是这么来的。");
        assertTrue(window <= MAX_MEMORY_WINDOW,
                "nexus.agent.memory.max-tokens 的默认值被调大到 " + window + "，超过上限 " + MAX_MEMORY_WINDOW + "。"
                        + "它是每轮发给模型的历史 token 数，直接等于 prefill 量，需要上限兜底。");
    }

    @Test
    @DisplayName("任何 yml 都不得声明 nexus.agent.memory.max-tokens（它会盖掉代码默认值）")
    void noYmlOverridesMemoryWindow() throws IOException {
        for (String rel : YML_FILES) {
            Path file = repoRoot().resolve(rel);
            if (!Files.exists(file)) {
                continue;
            }
            Object memory = dig(new Yaml().load(Files.readString(file)), "nexus", "agent", "memory");
            if (!(memory instanceof Map<?, ?> memoryMap)) {
                continue; // 没有 memory 段 = 走代码默认，正是我们想要的形态
            }
            assertFalse(memoryMap.containsKey("max-tokens"),
                    rel + " 里声明了 nexus.agent.memory.max-tokens=" + memoryMap.get("max-tokens")
                            + " —— profile 配置的优先级高于代码默认值，这一行会把 AgentProperties 的默认值盖掉。"
                            + " 2026-10-06 的线上事故正是 prod 残留的 max-tokens: 24000 干的："
                            + " 1M 上下文的模型实际只拿到 2.4 万 token 窗口，聊几轮就开始丢历史。"
                            + " 要调整窗口，改 AgentProperties.Memory.maxTokens 的默认值，全项目只此一处。");
        }
    }

    /**
     * 反向钉死「1M 模型不该被天花板砍到只剩零头」。
     * <p>
     * 这条把用户那句抱怨直接翻译成断言：声明了 100 万上下文的模型，
     * 生效窗口至少应该是它自己窗口的一个像样比例，而不是被全局天花板压成几万。
     */
    @Test
    @DisplayName("1M 上下文模型的生效窗口不得被全局天花板砍到 20% 以下")
    void largeContextModelKeepsUsableWindow() {
        int globalMax = new AgentProperties().getMemory().getMaxTokens();
        com.huzhijian.nexusagentweb.domain.Model mimo = new com.huzhijian.nexusagentweb.domain.Model();
        mimo.setName("mimo-v2.6-pro");
        mimo.setContextWindow(1_000_000);
        mimo.setMaxOutputTokens(32_768);

        int effective = com.huzhijian.nexusagentweb.model.ModelCapabilities.of(mimo).memoryWindow(globalMax);
        int minAcceptable = (int) (1_000_000 * 0.2);
        assertTrue(effective >= minAcceptable,
                "声明 1M 上下文的模型实际生效窗口只有 " + effective + " token（< 声明值的 20%）。"
                        + "全局天花板 nexus.agent.memory.max-tokens=" + globalMax + " 把它压得只剩零头 ——"
                        + " 用户会看到「明明是 1M 模型，聊几次就没上下文了」。要么调高默认值，"
                        + "要么承认这个天花板就是在替用户决定「你只能用这么点」并写进文档。");
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
