package com.huzhijian.nexusagentweb.properties;

import com.huzhijian.nexusagentweb.em.QuotaPeriod;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: Agent 运行时参数。
 * <p>
 * 此前这些值都写死在代码里（SSE 超时 120000L、记忆窗口 100000 且 token 估算器固定用 gpt-4o），
 * 换模型/调容量都要改代码重新编译。现在统一由 {@code nexus.agent.*} 配置驱动。
 * <p>
 * <b>所有字段都给了默认值</b>，因此即使 yml 里完全不写这一段也能正常启动
 * （本地 application-dev.yml 是 gitignore 的，不能指望它一定包含这些键）。
 * 示例见 {@code application-dev.yml.example}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nexus.agent")
public class AgentProperties {

    private Sse sse = new Sse();
    private Memory memory = new Memory();
    private Sandbox sandbox = new Sandbox();
    private Mcp mcp = new Mcp();
    private Security security = new Security();
    private Skill skill = new Skill();
    private Startup startup = new Startup();
    private Tools tools = new Tools();
    private Observability observability = new Observability();
    private Model model = new Model();
    private Quota quota = new Quota();
    private History history = new History();

    @Data
    public static class Sse {
        /**
         * SSE 连接超时。
         * <p>
         * 2026-10-03 从 120s 调到 1800s：agent 跑一个任务几分钟是常态，
         * 120s 会把正在干活的任务"看起来卡死"地掐断（前端表现为 pending 2 分钟后静默失败）。
         * <p>
         * ⚠️ 注意：**超时断开的只是连接，任务本身还会继续跑完并落库**（2026-10-03 起），
         * 用户刷新页面就能看到完整回复 —— 所以这个值不再是"任务的死刑"，
         * 只影响"这次连接能不能撑到任务结束"。
         * <p>
         * 另外长时间不吐字（工具执行中）可能被中间的 Nginx 等代理掐断（默认读超时 60s），
         * 后端已加 15s 一次的心跳注释帧保活（对前端透明，不影响事件流）。
         */
        private Duration timeout = Duration.ofSeconds(1800);

        /**
         * 流式增量的**合并阈值（字符数）**：缓冲攒够这么多字符就立即推送（P2-12）。
         * <p>
         * 调大 → SSE 帧数更少、网络与渲染更省，但首字延迟略增；
         * 调小 → 更"实时"，但帧数变多、前端渲染压力上升（卡顿的来源）。
         * 默认 200。
         */
        private int flushMaxChars = 200;

        /**
         * 流式增量的**合并时间阈值**：距上次推送超过这么久就立即推送，兜住低速内容
         * （比如模型一次只吐一两个字时，不该被 {@code flushMaxChars} 一直憋着）。
         * <p>
         * 默认 60ms —— 约等于"每秒最多约 16 次推送"，恰好一帧的间隔，人眼已看不出拼接感。
         */
        private Duration flushInterval = Duration.ofMillis(60);
    }

    @Data
    public static class Memory {
        /**
         * 对话记忆窗口的 token 上限。超出后旧消息会被淘汰（只影响送给模型的上下文，
         * 不影响数据库里已存的消息）。
         */
        private int maxTokens = 100000;

        /**
         * 用于估算 token 数的模型名。
         * <p>
         * 注意：这只是**估算器**（本地按分词规则算，不消耗 API）。原实现写死 gpt-4o，
         * 与实际使用的模型无关，会导致窗口裁剪不准。换了主力模型后建议同步改这里。
         */
        private String tokenEstimatorModel = "gpt-4o";

        /**
         * 一张图片按多少 token 估算（配额与记忆窗口裁剪用）。
         * <p>
         * 2026-10-03：图片消息从「URL 文本」改成了真正的 {@code ImageContent}，
         * 而 {@code OpenAiTokenCountEstimator} 不认识 ImageContent —— 一遇到就抛
         * {@code Unknown content type}（已实测），TokenWindowChatMemory 裁剪窗口时会直接炸。
         * 所以文本部分仍交给它算，图片部分按这个常数算。
         * <p>
         * 参考：OpenAI 的计费规则里，一张低细节图固定 85 token；1024px 中细节图约
         * 85 + 170×块数 ≈ 600~1100。取 1024 偏保守（宁可多估，避免超窗）。
         */
        private int imageTokens = 1024;

        /**
         * 单次长期记忆检索最多返回多少条（P2-7）。
         * <p>
         * 这些条目会直接拼进系统提示词，条数过多既费 token 又稀释重点。
         * 默认 20。
         */
        private int maxResults = 20;

        /**
         * 一次查询最多拆出多少个关键词（P2-7）。
         * <p>
         * 模型可能丢一整句话进来（"用户喜欢吃什么口味的菜"），
         * 拆太多词会让 OR 条件膨胀、命中变"什么都算相关"。默认 6。
         */
        private int maxKeywords = 6;

        /**
         * 是否启用 pg_trgm 相似检索兜底（P2-7 / D4）。
         * <p>
         * 字面匹配（{@code ILIKE '%kw%'}）一条都没命中时，再用
         * {@code similarity(content, kw)} 做一次模糊匹配，
         * 能救回"喜欢看科幻电影" ↔ "喜欢看科幻片"这类**部分重叠**的表述。
         * <p>
         * ⚠️ 它需要 <b>pg_trgm 扩展</b>（见 {@code docs/sql/006_add_user_memory_trgm_index.sql}）；
         * 没装也不会报错 —— 首次失败会被捕获并永久降级为纯字面匹配，只在日志里 WARN 一次。
         */
        private boolean fuzzy = true;

        /**
         * 相似检索的最低分值（0~1，P2-7）。
         * <p>
         * 中文短句的三元文法重叠率天然偏低（不像英文有空格和词形变化），
         * 沿用 PG 默认的 0.3 会漏掉不少相关项，故默认压到 0.15。
         * 觉得结果太杂就往上调。
         */
        private double fuzzyMinScore = 0.15;

        /**
         * 单条长期记忆的最大字符数，写入时截断（P2-7）。
         * 默认 500。
         */
        private int maxContentLength = 500;

        /**
         * 写入去重的相似度阈值（0~1，P2-7）。
         * <p>
         * 已有记忆与待写入内容的 {@code similarity()} 达到该值即判为重复、直接丢弃，
         * 避免模型反复保存同一条偏好把库撑爆、把检索结果污染。默认 0.85。
         */
        private double dedupThreshold = 0.85;
    }

    @Data
    public static class Sandbox {
        /**
         * 是否按会话复用沙盒。开启后同一会话内的多次工具调用复用同一个沙盒，
         * 避免每轮对话都新建（E2B 是按量计费的）。
         */
        private boolean reusePerSession = true;

        /**
         * 沙盒空闲回收时间。超过该时长没有使用的沙盒会被主动销毁。
         * <p>
         * ⚠️ 必须**小于** E2B 侧的超时（沙盒服务里 {@code set_timeout(600)} 即 10 分钟），
         * 否则我们还没来得及回收，E2B 就已经先回收了（那也不亏，只是回收逻辑空转）。
         */
        private Duration idleTimeout = Duration.ofMinutes(8);

        /** 后台回收任务的执行间隔 */
        private Duration sweepInterval = Duration.ofMinutes(1);

        /** 单次创建/操作沙盒的 HTTP 超时 */
        private Duration requestTimeout = Duration.ofSeconds(60);
    }

    @Data
    public static class Mcp {
        /** MCP 客户端健康检查超时 */
        private Duration healthTimeout = Duration.ofSeconds(5);

        /** 连接后是否缓存复用（避免每次对话都新建客户端导致连接泄漏） */
        private boolean cacheClients = true;
    }

    @Data
    public static class Security {
        /**
         * 是否启用登录鉴权（LoginCheckInterceptor）。
         * <p>
         * ⚠️ **默认 true，安全优先，不要为了本地方便改成默认 false。**
         * 本地调试若确实不想带 token，请在**自己不提交的** application-dev.yml 里显式关闭：
         * <pre>
         * nexus:
         *   agent:
         *     security:
         *       enabled: false
         * </pre>
         * 关闭时启动日志会打 WARN 提醒，避免出现「以为有鉴权、其实没有」的情况。
         * <p>
         * 历史做法是直接把 {@code @Configuration} 注释掉 —— 那样完全没有痕迹，
         * 容易被误提交，也看不出当前到底是什么状态。
         */
        private boolean enabled = true;
    }

    @Data
    public static class Skill {
        /**
         * 是否启用 Skill 能力。关闭后忽略请求里的 skills 参数。
         */
        private boolean enabled = true;

        /**
         * Skill 根目录。每个 skill 是一个子目录，内含 SKILL.md
         * （YAML frontmatter 提供 name / description），与 Claude Code 的约定一致。
         * <p>
         * 默认 {@code skills}（相对于应用工作目录）。支持 ~ 与相对路径。
         */
        private String rootDir = "skills";

        /**
         * 目录扫描结果缓存多久。设为 0 表示每次请求都重新扫描。
         * <p>
         * 有缓存时，新增 skill 目录最多延迟这段时间生效，不需要重启应用。
         */
        private Duration refreshInterval = Duration.ofSeconds(60);
    }

    @Data
    public static class Startup {
        /**
         * 启动配置自检发现**必需配置缺失**时是否 fail-fast 阻止启动。
         * <p>
         * 默认 {@code true}：缺配置就明确报错并列出清单，而不是带病启动、
         * 等到第一个请求进来才失败（见 {@code StartupConfigValidator}）。
         * <p>
         * ⚠️ 设为 {@code false} 只建议临时排查问题时用：缺失的必需配置仍然会导致
         * 对应能力不可用，启动日志会有 WARN 提示。
         */
        private boolean failFast = true;
    }

    @Data
    public static class Tools {
        /**
         * 工具发起的 HTTP 调用（沙盒服务等）的响应超时。
         * <p>
         * 默认 {@code 100s}：**刻意小于 {@code nexus.agent.sse.timeout}（120s）** ——
         * 这样超时先由工具层抛出、变成结构化的 {@code TIMEOUT} 结果回给模型，
         * 而不是把整条 SSE 流掐断（后者用户只看到断流，什么线索都没有）。
         * <p>
         * 长任务（沙盒里装依赖、跑大数据量脚本）可调大，但注意别超过 SSE 超时。
         */
        private Duration httpTimeout = Duration.ofSeconds(100);

        /**
         * 重复调用判定窗口。与 {@link #duplicateThreshold} 配合使用。
         */
        private Duration duplicateWindow = Duration.ofSeconds(60);

        /**
         * 窗口内允许「同一会话 + 同一工具 + 完全相同的参数」出现的最大次数，
         * 超过即拦截并回灌提示。设为 {@code 0} 或负数关闭该治理。
         * <p>
         * 默认 {@code 2}：即前两次放行、第三次起拦截。设为 1 会更激进（第二次就拦），
         * 但会误伤「失败后按相同参数重试一次」的合理场景。
         */
        private int duplicateThreshold = 2;
    }

    @Data
    public static class Observability {
        /**
         * 是否输出每次 Run 的指标汇总日志（`RUN runId=... tokens=... fee=... tools=...`）。
         * <p>
         * 默认开启：它是回答「刚才这次对话花了多少钱/调了什么工具」的唯一途径。
         * 关掉后不产生任何汇总日志（不影响对话本身）。
         */
        private boolean enabled = true;

        /**
         * 模型单价表：{@code key = 模型名或前缀 → 每 100 万 token 的单价（元）}。
         * <p>
         * **故意不给默认值**：各厂商价格经常变动，写死在代码里必然过时并给出错误金额。
         * 没配的模型汇总里显示 {@code fee=unpriced}（明确表示"不知道"，而不是显示 ¥0）。
         * <p>
         * 例：{@code deepseek: {input: 2, output: 8}} 可覆盖 {@code deepseek-v4-flash} 等
         * 以该前缀开头的模型（最长前缀优先）。
         */
        private Map<String, Price> modelPrices = new LinkedHashMap<>();
    }

    @Data
    public static class Price {
        /** 每 100 万输入 token 单价（元） */
        private double input;
        /** 每 100 万输出 token 单价（元） */
        private double output;
    }

    @Data
    public static class Model {
        /**
         * 服务商能力表：{@code key = baseUrl 中包含的片段 → 该服务商支持的参数}。
         * <p>
         * 用于解决「各厂商开思考/联网搜索的参数不一样，全局塞会 400」的问题（P2-3）：
         * 调用前按 baseUrl 判定，**只下发该服务商支持的参数**。
         * 内置了一份常见服务商的默认表（见 {@code ModelCapabilityResolver}），
         * 这里配置的同名项会**覆盖**内置，新片段则用于内置表没覆盖的中转/代理服务。
         * <p>
         * 例：<pre>
         * my-gateway.example.com:
         *   thinking: true
         *   search: true
         * </pre>
         * 匹配规则：baseUrl 包含 key（忽略大小写），多个命中取**最长**的 key。
         */
        private Map<String, ProviderCapability> providers = new LinkedHashMap<>();
    }

    /**
     * 系统内置模型列表（多供应商）。
     * <p>
     * 留空 = 不启用，沿用 {@code langchain4j.open-ai.streaming-chat-model} 建的单一默认模型
     * —— 这样老配置升级上来行为不变。
     */
    private List<SystemModel> systemModels = new ArrayList<>();

    @Data
    public static class ProviderCapability {
        /**
         * 是否支持「开/关思考」参数（{@code enable_thinking} / {@code thinking}）。
         * <p>
         * 默认 {@code false} —— **未知服务商一律不下发**，宁可少一个功能也不要 400。
         */
        private boolean thinking = false;

        /**
         * 是否支持联网搜索参数（{@code enable_search}）。
         * <p>
         * 默认 {@code false}；目前只有阿里云百炼（DashScope）系的 OpenAI 兼容接口认这个参数。
         */
        private boolean search = false;
    }

    /**
     * 一个**系统内置模型供应商**（没配自带 Key 的用户用的那批）。
     * <p>
     * 2026-10-03：原先系统默认模型只有 yml 里 {@code langchain4j.open-ai.streaming-chat-model}
     * 那一个 Bean —— 想加第二个、或换一家供应商都得改那一段，很不灵活。
     * 现在改成列表：**一家供应商一项，该项下可挂多个模型**（共用同一份 baseUrl / apiKey）。
     * <p>
     * 两种写法都支持：
     * <ol>
     *   <li><b>多模型（推荐）</b>：填 {@code models} 数组，每个元素一个模型：</li>
     * </ol>
     * <pre>
     * nexus:
     *   agent:
     *     system-models:
     *       - id: deepseek
     *         name: DeepSeek
     *         baseUrl: https://api.deepseek.com
     *         apiKey: ${DEEPSEEK}
     *         models:
     *           - modelName: deepseek-chat
     *             name: DeepSeek-V3
     *             contextWindow: 131072
     *             maxOutputTokens: 8192
     *           - modelName: deepseek-reasoner
     *             name: DeepSeek-R1
     *       - id: qwen
     *         name: 阿里云百炼
     *         baseUrl: https://dashscope.aliyuncs.com/compatible-mode/v1
     *         apiKey: ${ALI_AI_KEY}
     *         models:
     *           - modelName: qwen3-max
     *           - modelName: qwen-vl-max
     *             vision: true
     * </pre>
     * <p>
     * 模型级字段里**只有 {@code modelName} 必填**：{@code id} 与 {@code name} 不填时都自动
     * 退化成 {@code modelName}（见 {@code SystemModelRegistry#register}）。
     * {@code id} 仅在"两家供应商有同名模型"时需要单独指定，否则默认即可。
     * <ol start="2">
     *   <li><b>单模型（兼容旧写法）</b>：不写 {@code models}，直接在供应商项上写
     *       {@code modelName} —— 此时该项既是供应商也是唯一那个模型。</li>
     * </ol>
     * <p>
     * 字段继承：模型级的 {@code vision}/{@code contextWindow}/{@code maxOutputTokens}
     * 没填时，依次回退到**供应商级**同名配置，再没有才用默认值。
     * <p>
     * ⚠️ 不配这个列表时行为**完全不变**（仍用 langchain4j starter 建的单一默认模型）。
     */
    @Data
    public static class SystemModel {
        /** 供应商标识（仅用于分组展示，前端选模型用的是模型自己的 id） */
        private String id;
        /** 供应商展示名 */
        private String name;
        /** OpenAI 兼容地址 */
        private String baseUrl;
        /** 该服务商的 Key（建议写成占位符 ${XXX}） */
        private String apiKey;

        /**
         * 该供应商下的模型列表。**留空时按旧写法处理** —— 用本项的
         * {@code modelName} 作为唯一模型。
         */
        private List<ModelEntry> models = new ArrayList<>();

        // ---- 下面是"单模型旧写法"用的字段，写 models 时这些只作为**兜底默认值** ----
        /** 实际发给服务商的模型名（旧写法必填；新写法由 models[].modelName 提供） */
        private String modelName;
        /** 是否支持图片输入，默认 false */
        private Boolean vision;
        /** 上下文窗口，默认 256000 */
        private Integer contextWindow;
        /** 单次最大输出 token，默认 32000 */
        private Integer maxOutputTokens;

        /**
         * 供应商下的一个模型条目。
         * <p>
         * ⚠️ <b>只有 {@code modelName} 是必填的</b>，{@code id} / {@code name} 都能省略。
         */
        @Data
        public static class ModelEntry {
            /**
             * 模型在本系统内的唯一键；前端 {@code model.id} 传它即可选中。
             * <p>
             * <b>一般不用写</b> —— 不填时自动等于 {@code modelName}。
             * 只有"两家供应商有同名模型"（如百炼和某代理都叫 deepseek-chat）时才需要单独指定，
             * 否则两行会撞同一个 id、后者覆盖前者。
             */
            private String id;
            /** 展示名；不填则用 modelName（想让选择器显示得好看点才写，如 "DeepSeek-V3"） */
            private String name;
            /** 实际发给服务商的模型名（**必填**，其余字段缺省都由它兜底） */
            private String modelName;
            /** 是否支持图片输入；不填时继承供应商级，再没有则 false */
            private Boolean vision;
            /** 上下文窗口；不填时继承供应商级，再没有则 256000 */
            private Integer contextWindow;
            /** 单次最大输出 token；不填时继承供应商级，再没有则 32000 */
            private Integer maxOutputTokens;
        }
    }

    @Data
    public static class Quota {
        /**
         * 是否启用 token 配额校验（P2-8）。
         * <p>
         * 关闭后不再拦截超支用户，但**用量仍会照常累加**（不影响可观测性与后续统计）。
         */
        private boolean enabled = true;

        /**
         * 新注册用户的默认 token 配额。{@code <= 0} 表示不限制（默认）。
         * <p>
         * 存量用户不受影响 —— 他们的 {@code users.token_quota} 为 NULL，同样视为不限制；
         * 要限谁就在库里给谁设值（或用管理接口，当前没有）。
         */
        private long defaultQuota = 0;

        /**
         * token 配额的**重置周期**（P2-8 遗留）：{@code NONE} / {@code DAILY} / {@code MONTHLY}。
         * <p>
         * 默认 {@code NONE} = 不重置（{@code token_used} 累计只增不减），
         * 与 {@code docs/sql/003} 的既有行为完全一致，不影响任何存量用户。
         * <p>
         * 这是**全局默认值**；单个用户可在库里覆盖
         * （{@code UPDATE users SET token_period='MONTHLY' WHERE id=...}，见 {@code docs/sql/007}）。
         * 周期按**服务端默认时区**计算。
         */
        private QuotaPeriod period = QuotaPeriod.NONE;
    }

    @Data
    public static class History {
        /**
         * 会话搜索：最多扫多少条**命中消息**（P3-1 补）。
         * <p>
         * 注意单位是「消息」不是「会话」——SQL 先按时间倒序取一批命中消息，
         * 再在内存里按会话聚合。之所以要在 SQL 层就截断：
         * 热门关键词（比如搜「的」）能在大库里命中几十万行，不截断会拖垮数据库。
         */
        private int searchMaxRows = 300;

        /**
         * 会话搜索：最终最多返回多少个**会话**。
         */
        private int searchMaxSessions = 30;

        /**
         * 会话搜索：命中片段在关键词前后各保留多少个字符。
         */
        private int snippetRadius = 40;
    }
}
