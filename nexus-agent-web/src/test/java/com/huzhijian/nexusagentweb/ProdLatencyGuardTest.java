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
    @DisplayName("记忆窗口默认值：既要有上限，也不能小到装不下一个正常会话")
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
     * （确实有 1M 上下文的模型），真正决定 prefill 量的是
     * {@code memoryWindow = min(max-tokens, contextWindow - maxOutputTokens)}。
     * 所以这里算一遍实际值再断言，既钉死首字预算，又不误伤真实的大窗口模型。
     * <p>
     * 🔴 <b>2026-10-06 晚间：这里原来测错了对象。</b>
     * {@code globalMax} 直接取 {@code new AgentProperties()} 的<b>代码默认值</b>，
     * 而 prod yml 里当时白纸黑字写着 {@code max-tokens: 24000} ——
     * profile 属性源优先级更高，线上真正生效的是 24000，本测试却按 300000 算，
     * 于是「1M 模型实际只剩 2.4 万 token 窗口」这件事在三个护栏全绿的情况下上线了。
     * <b>读配置文件的测试，就必须读那份文件里写了什么，不能读默认值。</b>
     */
    @Test
    @DisplayName("prod 里每个系统模型的实际生效窗口：要有上限，也不能小到装不下一个会话")
    void prodSystemModelsEffectiveWindowStaysReasonable() throws IOException {
        Path prod = repoRoot().resolve("nexus-agent-web/src/main/resources/application-prod.yml");
        assertTrue(Files.exists(prod), "找不到 application-prod.yml");

//        ⚠️ 必须用「prod 实际声明的值」而不是代码默认值：
//        prod 的属性源优先级高于 AgentProperties，写了就是它说了算。
        int globalMax = declaredMemoryMaxTokens(prod);
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

    /**
     * 取某个 profile yml 里<b>实际声明</b>的 {@code nexus.agent.memory.max-tokens}；
     * 没声明就回落到代码默认值（那时确实是代码默认说了算）。
     * <p>
     * 存在的原因见 {@link #prodSystemModelsEffectiveWindowStaysReasonable} 的注释 ——
     * 用默认值去算「prod 的生效窗口」正是 2026-10-06 那次漏网的直接原因。
     */
    private static int declaredMemoryMaxTokens(Path profileYml) throws IOException {
        Object declared = dig(new Yaml().load(Files.readString(profileYml)),
                "nexus", "agent", "memory", "max-tokens");
        if (declared instanceof Number n) {
            return n.intValue();
        }
        if (declared instanceof String s && !s.isBlank()) {
//            形如 "24000"；解析不了就当成没声明，让调用方走默认值分支
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return new AgentProperties().getMemory().getMaxTokens();
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
        ModelCapabilities caps = ModelCapabilities.of(meta);
        int effective = caps.memoryWindow(globalMax);
//        modelName 为 null = 检查的是**供应商级**默认值（旗下模型不填元数据时会拿到多大窗口），
//        说清楚，别在报错里留一个裸 null 让人以为是解析坏了。
        String who = (modelName == null
                ? "供应商 " + providerId + " 的**供应商级默认**窗口"
                : "系统模型 " + modelName + "（供应商 " + providerId + "）");

        assertTrue(effective <= MAX_MEMORY_WINDOW,
                who + "实际生效的记忆窗口是 " + effective
                        + " token，超出上限 " + MAX_MEMORY_WINDOW
                        + " —— 请给该模型填真实的 contextWindow / maxOutputTokens。");

        if (effective >= MIN_USEFUL_WINDOW) {
            return;
        }
//        🔴 两种「窗口太小」的成因完全不同，修法也完全不同 —— 报错时必须分开说，
//        否则下次排查会被带到错误方向（2026-10-06 反向验证时亲历：
//        真正卡在全局天花板，报错却说「模型元数据填得太小」）。
        int modelSide = caps.contextWindow() - caps.maxOutputTokens();
        if (globalMax < MIN_USEFUL_WINDOW) {
            assertTrue(false,
                    who + "实际生效的记忆窗口只有 " + effective + " token（< " + MIN_USEFUL_WINDOW + "）—— "
                            + "卡在**全局天花板** nexus.agent.memory.max-tokens=" + globalMax + " 这一侧。"
                            + " 模型自己的窗口是 " + caps.contextWindow() + "、输出上限 " + caps.maxOutputTokens()
                            + "（可容纳 " + modelSide + " token），完全够用，是被这个全局值压住了。"
                            + " 表现为「1M 上下文的模型聊几轮就开始忘事」。"
                            + " 请调高 AgentProperties.Memory.maxTokens 的默认值；"
                            + " 若这个值来自某个 profile yml，那份文件才是罪魁祸首（见 MemoryWindowDriftTest）。");
        }
        assertTrue(false,
                who + "实际生效的记忆窗口只有 " + effective + " token（< " + MIN_USEFUL_WINDOW + "）—— "
                        + "卡在**模型元数据** contextWindow - maxOutputTokens = " + modelSide + " 这一侧"
                        + "（全局天花板 " + globalMax + " 并没有卡住它）。"
                        + " 该模型的元数据填得太小，会让它**静默丢掉更早的历史**，"
                        + " 表现为「聊了几十轮就开始忘事」。"
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
