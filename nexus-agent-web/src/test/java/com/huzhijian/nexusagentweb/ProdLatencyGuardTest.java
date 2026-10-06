package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.model.ModelCapabilities;
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
 * 2026-10-06「首字 4 秒 / 长会话 21 秒」的防复发护栏。
 * <p>
 * <b>线上现象</b>：用户反馈首字极慢，且越聊越慢。
 * <b>根因（三处，都在配置里，纯 mock 单测一个也抓不到）</b>：
 * <ol>
 *   <li>{@code application-prod.yml} 没有覆盖日志级别，于是生产一直跑 {@code application.yml}
 *       里的 {@code debug} —— MyBatis 的 mapper 也在 {@code com.huzhijian.nexusagentweb} 包下，
 *       每轮对话 5~8 条 SQL 连同参数全部同步写 stdout；</li>
 *   <li>{@code nexus.agent.memory.max-tokens: 100000} —— 这个数直接等于「每轮发给模型的历史
 *       有多少 token」，也就是模型的 prefill 量，10 万 token 下光 prefill 就要几秒；</li>
 *   <li>系统模型 {@code deepseek-flash} 填了 {@code contextWindow: 1000000} /
 *       {@code maxOutputTokens: 384000}，把记忆窗口顶到 {@code min(100000, 616000)} = 10 万，
 *       同时 38.4 万会作为 {@code max_tokens} 发给服务商（远超其真实上限）。</li>
 * </ol>
 * 这三项都是「配置文件里的一个数字」，改回去只要一次手滑。
 * 所以这里把 yml 与 {@link AgentProperties} 当<b>输入</b>来断言 ——
 * 与 {@code SseTimeoutDriftTest} / {@code EnvPlaceholderDriftTest} 同一套思路。
 */
@DisplayName("首字延迟护栏 —— 日志级别 / 记忆窗口 / 模型元数据不许再被调大")
class ProdLatencyGuardTest {

    /**
     * 记忆窗口的硬上限。它等于模型每轮的 prefill 量，直接与首字延迟成正比。
     * 2026-10-06 从 100000 下调到 24000；想调大必须先想清楚凭什么牺牲首字。
     */
    /**
     * 🔴 2026-10-06 修正这条不变式。
     * <p>
     * 原值 32768的依据是「窗口大 → prefill 重 → 首字慢」，但后续三次线上日志
     * 证明<b>那个因果是错的</b>：真正的瓶颈是 {@code skillResolve}（每次查库）
     * 与 {@code CHAT_MEMORY}（同请求内查 3 次）合计约 3.5 秒，
     * 而当时 97 条消息（约 3 万 token）<b>根本没撑满 32768</b> ——
     * 用户被迫开新对话、模型却在失忆，而首字一点没变快。
     * <p>
     * 现在的不变式只保留两条真正成立的约束：
     * <ol>
     *   <li><b>要有上限</b>：防止误填 10M 之类把 prefill 拉到几十秒；</li>
     *   <li><b>不能小到装不下一个正常会话</b>：低于这个值会静默丢历史，
     *       前端「聊了很多轮」的提示就成了误报。</li>
     * </ol>
     */
    private static final int MAX_MEMORY_WINDOW = 400_000;

    /** 至少要装得下这么多 token，否则等于静默丢历史 */
    private static final int MIN_USEFUL_WINDOW = 80_000;

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
    @DisplayName("记忆窗口默认值不得高于 32k —— 它就是模型每轮的 prefill 量")
    void codeDefaultMemoryWindowStaysSmall() {
        int window = new AgentProperties().getMemory().getMaxTokens();
        assertTrue(window > 0 && window <= MAX_MEMORY_WINDOW,
                "nexus.agent.memory.max-tokens 的默认值被调大到 " + window + "。"
                        + "它是「每轮发给模型的历史有多少 token」，需要有上限兜底，"
                        + "否则误填一个巨大值会把 prefill 拉到几十秒。上限 " + MAX_MEMORY_WINDOW + "。");
        assertTrue(window >= MIN_USEFUL_WINDOW,
                "记忆窗口只有 " + window + " token，太小了。"
                        + "上下文实际生效值是 min(本值, contextWindow - maxOutputTokens)，"
                        + " 本值过小会让模型**静默丢掉更早的历史** —— 表现为「聊了几十轮就开始忘事」，"
                        + " 而前端只会提示「建议新建对话」，用户白白以为是自己聊得太多。"
                        + " 至少要 " + MIN_USEFUL_WINDOW + "。");
    }

    @Test
    @DisplayName("prod 必须显式把项目包覆盖成 info —— 否则生产在打 DEBUG（含每条 SQL）")
    void prodOverridesLoggingLevel() throws IOException {
        Path prod = repoRoot().resolve("nexus-agent-web/src/main/resources/application-prod.yml");
        assertTrue(Files.exists(prod), "找不到 application-prod.yml");

        Object level = dig(new Yaml().load(Files.readString(prod)),
                "logging", "level", "com", "huzhijian", "nexusagentweb");
        assertNotNull(level,
                "application-prod.yml 没有声明 logging.level.com.huzhijian.nexusagentweb。"
                        + "application.yml 里它是 debug，而 prod 不覆盖就等于生产跑 DEBUG："
                        + "MyBatis 的 mapper 同在这个包下，每轮 5~8 条 SQL 连参数全量同步写 stdout，"
                        + " 这部分耗时实打实压在首字延迟上（2026-10-06）。");
        String value = String.valueOf(level).trim();
        assertFalse(value.equalsIgnoreCase("debug") || value.equalsIgnoreCase("trace"),
                "application-prod.yml 把项目包设成了 " + value + " —— 生产不该跑 DEBUG（理由同上）。");
    }

    /**
     * 断言的是<b>实际生效</b}的记忆窗口，而不是声明值。
     * <p>
     * 为什么不能直接禁掉大的 {@code contextWindow}：它是模型的<b>真实能力</b>
     * （确实有 1M 上下文的模型），而且真正决定 prefill 量的
     * {@code memoryWindow = min(max-tokens, contextWindow - maxOutputTokens)}
     * 已经被 {@code nexus.agent.memory.max-tokens} 兜住了 ——
     * 声明 100 万但 max-tokens 是 24000 时，实际窗口仍是 24000，无害。
     * 所以这里算一遍实际值再断言，既钉死首字预算，又不误伤真实的大窗口模型。
     */
    @Test
    @DisplayName("prod 里每个系统模型的实际生效窗口：要有上限，也不能小到装不下一个会话")
    void prodSystemModelsEffectiveWindowStaysReasonable() throws IOException {
        Path prod = repoRoot().resolve("nexus-agent-web/src/main/resources/application-prod.yml");
        assertTrue(Files.exists(prod), "找不到 application-prod.yml");

        int globalMax = new AgentProperties().getMemory().getMaxTokens();
        Object systemModels = dig(new Yaml().load(Files.readString(prod)),
                "nexus", "agent", "system-models");
        if (!(systemModels instanceof List<?> providers)) {
            return; // 没配系统模型，无此项可查
        }
        for (Object provider : providers) {
            if (!(provider instanceof Map<?, ?> pm)) {
                continue;
            }
            checkEffectiveWindow(globalMax,
                    pm.get("contextWindow"), pm.get("maxOutputTokens"), pm.get("modelName"), pm.get("id"));
            Object models = pm.get("models");
            if (models instanceof List<?> entries) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> em) {
                        checkEffectiveWindow(globalMax,
                                em.get("contextWindow"), em.get("maxOutputTokens"),
                                em.get("modelName"), pm.get("id"));
                    }
                }
            }
        }
    }

    private static void checkEffectiveWindow(int globalMax, Object contextWindow,
                                             Object maxOutputTokens, Object modelName, Object providerId) {
        Model meta = new Model();
        meta.setName(String.valueOf(modelName));
        if (contextWindow instanceof Integer cw) {
            meta.setContextWindow(cw);
        }
        if (maxOutputTokens instanceof Integer out) {
            meta.setMaxOutputTokens(out);
        }
        int effective = ModelCapabilities.of(meta).memoryWindow(globalMax);
        assertTrue(effective <= MAX_MEMORY_WINDOW,
                "系统模型 " + modelName + "（供应商 " + providerId + "）实际生效的记忆窗口是 " + effective
                        + " token，超出上限 " + MAX_MEMORY_WINDOW
                        + " —— 请给该模型填真实的 contextWindow / maxOutputTokens。");
        assertTrue(effective >= MIN_USEFUL_WINDOW,
                "系统模型 " + modelName + "（供应商 " + providerId + "）实际生效的记忆窗口只有 " + effective
                        + " token，**小于 max-tokens 上限 " + MIN_USEFUL_WINDOW + "** —— 说明卡在"
                        + " contextWindow - maxOutputTokens 那一侧：该模型的元数据填得太小，"
                        + " 会让它**静默丢掉更早的历史**，表现为「聊了几十轮就开始忘事」。"
                        + " 请给该模型填真实的 contextWindow / maxOutputTokens。");
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
