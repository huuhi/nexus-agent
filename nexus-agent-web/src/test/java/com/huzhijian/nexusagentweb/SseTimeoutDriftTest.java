package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-05「任务跑满 2 分钟被掐断」的防复发护栏。
 * <p>
 * <b>线上现象</b>：agent 任务跑满 2 分钟，SSE 流被掐断（前端截图里 stream 的 Time 恰好 2 min）。
 * <b>根因</b>：2026-10-03 的修复把 {@code AgentProperties.Sse.timeout} 的<b>默认值</b>从 120s
 * 调到 1800s，但 {@code application-prod.yml} 里残留的 {@code timeout: 120s} 没删 ——
 * 而 profile 属性源优先级<b>高于</b>代码默认值，用户又以 prod profile 运行，
 * 于是 1800s 被 120s 盖掉，{@code new SseEmitter(120_000)} 满两分钟必断。
 * <p>
 * 这类「两份配置不同步」用纯 mock 单测永远抓不到（它不读 yml），
 * 所以把 yml 当<b>输入</b>来断言 —— 与 {@code EnvPlaceholderDriftTest} 同一套思路。
 * <p>
 * 钉死的不变式：<b>SSE 超时的唯一事实源是 {@code AgentProperties.Sse.timeout}，
 * 任何 profile yml 都不许声明这个键。</b>
 */
@DisplayName("SSE 超时单一事实源（防 profile 配置盖掉代码默认值）")
class SseTimeoutDriftTest {

    /**
     * 默认超时的下限。2026-10-03 / 2026-10-05 两次线上事故都是「任务几分钟是常态」
     * 而超时太短造成的 —— 想调小必须先想清楚凭什么。
     */
    private static final Duration MIN_DEFAULT_TIMEOUT = Duration.ofSeconds(1800);

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
    @DisplayName("AgentProperties 的默认超时不得低于 1800s（防止修复被悄悄改回去）")
    void codeDefaultStaysLongEnough() {
        Duration timeout = new AgentProperties().getSse().getTimeout();
        assertTrue(timeout.compareTo(MIN_DEFAULT_TIMEOUT) >= 0,
                "SSE 默认超时被调小了（当前 " + timeout + "）。"
                        + "agent 跑一个任务几分钟是常态，低于这个值会把正在干活的任务"
                        + "「看起来卡死」地掐断 —— 2026-10-03 与 2026-10-05 两次事故同源");
    }

    @Test
    @DisplayName("任何 yml（主配置 / prod / dev 模板 / 部署覆盖文件）都不得声明 nexus.agent.sse.timeout")
    void noYmlOverridesSseTimeout() throws IOException {
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
            Object sse = dig(new Yaml().load(Files.readString(file)), "nexus", "agent", "sse");
            if (!(sse instanceof Map<?, ?> sseMap)) {
                continue; // 没有 sse 段 = 走代码默认，正是我们想要的形态
            }
            assertFalse(sseMap.containsKey("timeout"),
                    rel + " 里声明了 nexus.agent.sse.timeout=" + sseMap.get("timeout")
                            + " —— profile 配置的优先级高于代码默认值，这一行会把 AgentProperties 的"
                            + " 1800s 盖掉，SseEmitter 到点就被 Tomcat 掐断（表现为任务跑满你写的时长必断）。"
                            + " 2026-10-05 的线上事故正是 prod 残留的 timeout: 120s 干的。"
                            + " 要调整超时，改 AgentProperties.Sse.timeout 的默认值，全项目只此一处。");
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
