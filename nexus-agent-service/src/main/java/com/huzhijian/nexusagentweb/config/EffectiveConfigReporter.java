package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
     * 联网搜索 / 正文提取共用的 API Key。
     * <p>
     * 🔴 必须与 {@code WebSearchTool} / {@code WebExtractTool} <b>用同一个表达式</b>
     * （都引用 {@link AgentProperties.Websearch#API_KEY_EXPRESSION}），
     * 否则会出现最糟的一种状态：<b>快照说"已启用"，实际工具没注册</b>（或反过来），
     * 而这个快照存在的全部意义就是回答「到底配到了没有」。
     * <p>
     * ⚠️ 不能是 {@code final}（常量折叠会让读取恒为字面量，See {@code ValueInjectionGuardTest}），
     * 这里走的是非 final 字段注入 —— 本类是纯观测组件，字段注入不影响任何业务时序。
     */
    @Value(AgentProperties.Websearch.API_KEY_EXPRESSION)
    private String tavilyApiKey;

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

//        ===== 联网搜索 / 正文提取：开关状态必须能在启动日志里一眼看到 =====
//        2026-10-08：用户反馈「我在服务器配了 TAVILY_API_KEY，但 AI 说没有这个工具」。
//        这类问题的根因是**配置没进到读取方**（配了没重启 / 容器没重建 / 写在了读不到的地方），
//        而在此之前它**完全没有可见的痕迹** —— 工具只是静默不注册，模型那边就像没这个能力。
//        所以把状态（含 Key 的长度与形态、以及**来源是哪一条**）打进这张快照。
//        2026-10-09：读取方式从 System.getenv 改成「配置项优先 + 环境变量兜底」，
//        本行也跟着改读同一个源，并额外报出「命中的是哪一条」——
//        因为「写在 override.yml 里却说没配」正是那天的现场。
        String tavilyStatus = describeTavilyKey();
        rows.add(row("联网搜索(web_search)", AgentProperties.Websearch.API_KEY_PROPERTY, tavilyStatus,
                "🔴 两条路任选其一：配置项 nexus.agent.websearch.api-key（外部 yml / 面板 .env.properties）"
                        + "或环境变量 TAVILY_API_KEY；没配时工具集**不注册** —— 模型看不到 web_search，而不是调用了才报错"));
        rows.add(row("正文提取(web_extract)", AgentProperties.Websearch.API_KEY_PROPERTY, tavilyStatus,
                "与 web_search 共用同一个 Key 与同一个免费额度池"));

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
     * {@code web_search} / {@code web_extract} 那个 Key 的状态描述。
     * <p>
     * 🔴 <b>绝不打印 Key 本体</b>，只报「有没有 / 多长 / 形态对不对 / 从哪来」——
     * 这几条足以定位「配了却不生效」，又不会把凭据写进日志（日志是会外发、会归档的）。
     * <p>
     * 为什么连形态都要报：Key 从网页上复制时经常少复制几位或多带空格，
     * 那种情况是"有值"的、工具也注册了，但一调用就是 401 —— 有了形态提示，不用等到调用才发现。
     * <p>
     * 为什么要报<b>来源</b>：2026-10-09 的现场就是「用户把 Key 写在
     * {@code nexus-override.yml} 里，日志却说没配置」。当时读取方用的是 {@code System.getenv}，
     * 看不见配置文件。现在两条路都通了，于是日志有义务说清楚<b>到底是哪一条命中的</b> ——
     * 否则用户仍然无法判断自己写的那处有没有被读到（尤其是同时写了两处、而其中一处写错的时候）。
     */
    private String describeTavilyKey() {
        String raw = tavilyApiKey == null ? "" : tavilyApiKey.trim();
        if (raw.isEmpty()) {
            return "未配置（配置项 " + AgentProperties.Websearch.API_KEY_PROPERTY + " 与 env TAVILY_API_KEY 都没有值）";
        }
        String shape = raw.startsWith("tvly-") ? "形态正确" : "⚠️ 前缀不是 tvly-，请确认复制完整";
        return "已启用（长度 " + raw.length() + "，" + shape + "；来源：" + tavilyKeySource() + "）";
    }

    /**
     * Key 值命中的是哪一条路径：配置项（外部 yml / 面板 .env.properties）还是环境变量。
     * <p>
     * 判据是「配置项这个键在 Environment 里存不存在」：不存在就说明值是靠环境变量兜底拿到的。
     * 全程 try/catch —— 本类只是观测手段，<b>绝不能因为任意环境实现（含测试里的 mock）
     * 把应用启动搞挂</b>。
     */
    private String tavilyKeySource() {
        if (environment == null) {
            return "未知（无 Environment）";
        }
        try {
            if (environment.containsProperty(AgentProperties.Websearch.API_KEY_PROPERTY)) {
                return "配置项 " + AgentProperties.Websearch.API_KEY_PROPERTY;
            }
        } catch (Exception e) {
            return "未知（读取配置源失败：" + e.getClass().getSimpleName() + "）";
        }
        return "环境变量 TAVILY_API_KEY";
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