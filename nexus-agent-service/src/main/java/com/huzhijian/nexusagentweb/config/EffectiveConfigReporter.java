package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * 🔴 启动时打印「生效配置快照」（2026-10-06，用户要求显式化默认行为的第一条）。
 *
 * <h3>为什么必须有它</h3>
 * <p>
 * 2026-10-06 这一整天，「某个值到底来自 jar 内还是被外部配置覆盖了」反复成为排查障碍：
 * <ul>
 *   <li>日志里 {@code ctx=1000000 out=384000} 出现了三次，我一直以为用户没改配置，
 *       催了三次；实际那<strong>就是他的模型真实参数</strong>（jar 与外部文件都是这个值）。</li>
 *   <li>反过来，我改了 jar 内的 {@code memory.max-tokens} 与模型元数据，
 *       却因为外部 {@code conf/nexus-override.yml} 里那份 {@code system-models}
 *       <strong>整体顶掉</strong>了 jar 内的版本，改动<b>静默不生效</b>。</li>
 * </ul>
 * 两次都因为「看不出来这个值是从哪来的」。而 Spring 提供了答案：
 * {@code Environment#getProperty} 能同时看到<b>最终生效值</b>与<b>它来自哪个属性源</b>。
 * <p>
 * 所以这个类做两件事：
 * <ol>
 *   <li>把影响行为的配置逐项打成一张表 —— <b>值</b>与<b>来源</b>并排；</li>
 *   <li>被外部文件覆盖的项<b>显式标出</b>，因为那正是「改了 jar 却没生效」的现场。</li>
 * </ol>
 *
 * <h3>为什么不在 {@code StartupConfigValidator} 里做</h3>
 * <p>
 * 那个类只回答「<b>该有的有没有</b>」（缺必需配置就 fail-fast）；
 * 这个类回答「<b>现在到底是什么</b>」—— 两者都要，且后者的优先级是「知道了也不一定要改」。
 *
 * @author 胡志坚
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffectiveConfigReporter {

    private final AgentProperties agentProperties;
    /** 🔴 必须是 ConfigurableEnvironment：{@link Environment} 接口没有 {@code getPropertySources()}，
     * 而「这个值来自哪个属性源」正是本类的核心目的。 */
    private final ConfigurableEnvironment environment;

    /**
     * {@code system-models} 是<b>列表</b>配置：外部文件里写一份就会
     * <b>整个顶掉</b> jar 内那份（漏写的条目会静默消失，见 AGENTS.md 的记录）。
     * 这里单独检查一次 —— 它是「改了 jar 不生效」的头号原因。
     */
    private static final String SYSTEM_MODELS_KEY = "nexus.agent.system-models[0].model-name";

    @PostConstruct
    public void report() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(78)).append("\n");
        sb.append("  生效配置快照（值 ← 来源）。外部覆盖的文件会标 [外部覆盖]，")
                .append("改 jar 内配置却不生效多半就是它。\n");
        sb.append("=".repeat(78)).append("\n");

        List<String[]> rows = new ArrayList<>();
        AgentProperties.Memory memory = agentProperties.getMemory();

        rows.add(row("记忆窗口上限(每轮发给模型的历史 token)", "nexus.agent.memory.max-tokens",
                String.valueOf(memory.getMaxTokens()),
                "🔴 这个数直接等于每轮的 prefill 量，与首字延迟成正比"));
        rows.add(row("历史条数上限", "nexus.agent.memory.max-history-messages",
                String.valueOf(memory.getMaxHistoryMessages()),
                "⚠️ 与上面的 token 窗口是**两套独立机制**，别以为改了 token 就不受它约束"));
        rows.add(row("token 估算模型", "nexus.agent.memory.token-estimator-model",
                memory.getTokenEstimatorModel(),
                "换它会改变「估算」结果，从而改变实际保留多少历史"));
        rows.add(row("单图 token 估算", "nexus.agent.memory.image-tokens",
                String.valueOf(memory.getImageTokens()), "图片消息的固定估算值"));
        rows.add(row("SSE 超时", "nexus.agent.sse.timeout",
                String.valueOf(agentProperties.getSse().getTimeout()),
                "超时会断开连接，但**任务仍继续跑完并落库**"));
        rows.add(row("技能总开关", "nexus.agent.skill.enabled",
                String.valueOf(agentProperties.getSkill().isEnabled()), null));
        rows.add(row("技能目录缓存", "nexus.agent.skill.refresh-interval",
                String.valueOf(agentProperties.getSkill().getRefreshInterval()),
                "0 = 每次请求都重扫目录"));
        rows.add(row("技能根目录", "nexus.agent.skill.root-dir",
                agentProperties.getSkill().getRootDir(),
                "⚠️ 绝不能指向用户可写的目录"));
        rows.add(row("配额总开关", "nexus.agent.quota.enabled",
                String.valueOf(agentProperties.getQuota().isEnabled()), null));
        rows.add(row("新用户默认角色", "nexus.agent.quota.default-role",
                String.valueOf(agentProperties.getQuota().getDefaultRole()),
                "TEST / VIP 不由注册接口产出，只能运营在库里授予"));
        rows.add(row("工具 HTTP 超时", "nexus.agent.tools.http-timeout",
                String.valueOf(agentProperties.getTools().getHttpTimeout()), null));
        rows.add(row("重复调用判定窗口", "nexus.agent.tools.duplicate-window",
                String.valueOf(agentProperties.getTools().getDuplicateWindow()),
                "⚠️ 同工具同参数超过 " + agentProperties.getTools().getDuplicateThreshold()
                        + " 次才拦 —— 拦的是**完全相同**的参数，换个关键词就拦不住"));
        rows.add(row("重复调用阈值", "nexus.agent.tools.duplicate-threshold",
                String.valueOf(agentProperties.getTools().getDuplicateThreshold()),
                "线上见过 search_user_memory 连调 5 次（每次关键词不同 → 拦不住）"));

        int nameWidth = rows.stream().mapToInt(r -> r[0].length()).max().orElse(10);
        int valWidth = rows.stream().mapToInt(r -> r[2].length()).max().orElse(10);
        valWidth = Math.min(valWidth, 34);

        for (String[] r : rows) {
            String key = r[1];
            String value = r[2];
            String source = sourceOf(key);
            boolean overridden = !"jar/application.yml".equals(source) && !source.isEmpty();
            sb.append(String.format("  %-" + nameWidth + "s  %-" + valWidth + "s  ← %s%s%n",
                    r[0], value.length() > valWidth ? value.substring(0, valWidth - 1) + "…" : value,
                    source, overridden ? "  🔴 [外部覆盖]" : ""));
            if (r[3] != null) {
                sb.append("  ").append(" ".repeat(nameWidth)).append("    ↳ ").append(r[3]).append("\n");
            }
        }

        // system-models 单独查：列表配置被外部顶掉是最常见的一种「改了不生效」
        Object firstModel = environment == null ? null : environment.getProperty(SYSTEM_MODELS_KEY);
        sb.append("  系统模型(").append(SYSTEM_MODELS_KEY).append(")  ")
                .append(firstModel == null ? "<未配置>" : firstModel)
                .append("  ← ").append(sourceOf("nexus.agent.system-models")).append("\n");
        if (firstModel == null) {
            sb.append("    ↳ 🔴 没读到任何系统模型 —— 对话会走 langchain4j 的单一默认模型，")
                    .append("模型选择器会是空的。\n");
        } else {
            sb.append("    ↳ ⚠️ 这是**列表**配置：外部文件里写一份就会**整个顶掉** jar 内那份，")
                    .append("漏写的条目会静默消失。改 jar 内配置前先确认它的来源。\n");
        }

        sb.append("=".repeat(78));
        log.info(sb.toString());
    }

    private static String[] row(String name, String key, String value, String note) {
        return new String[]{name, key, value, note};
    }

    /**
     * 这个 key 的值来自哪个属性源。
     * <p>
     * 遍历顺序里 Spring Boot 的 {@code application.yml} 在前、外部配置文件在后，
     * 所以 {@code getPropertySources().last()} 命中哪个源，就是「最终生效」的那个。
     */
    private String sourceOf(String key) {
        // 🔴 getPropertySources() 理论上有实现，但**不能假设它非 null**：
        // 测试里用一个只 stub 了 getProperty 的 Environment 就会拿到 null。
        // 本类纯观测，绝不能因为这种输入把应用启动搞挂。
        if (environment == null || environment.getPropertySources() == null) {
            return "<未知>";
        }
        // ⚠️ PropertySources 是 Iterable 而非 Collection，for-each 之前要先物化成 List
        List<org.springframework.core.env.PropertySource<?>> sources = new ArrayList<>();
        environment.getPropertySources().forEach(sources::add);
        for (org.springframework.core.env.PropertySource<?> ps : sources) {
            if (ps.containsProperty(key)) {
                String name = ps.getName();
                // 屏蔽口令等敏感来源名细节，只保留可判断的关键词
                if (name.contains("nexus-override") || name.contains("SPRING_CONFIG_ADDITIONAL")) {
                    return "外部覆盖文件(" + name + ")";
                }
                return name;
            }
        }
        return "<未配置>";
    }
}