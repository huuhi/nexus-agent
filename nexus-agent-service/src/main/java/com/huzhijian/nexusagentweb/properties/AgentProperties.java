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
    private Upload upload = new Upload();
    private Websearch websearch = new Websearch();

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
         * <p>
         * 🔴 <b>2026-10-06：从 100000 下调到 24000。</b>
         * <p>
         * 这个值不是"内存/磁盘"参数，而是<b>每轮发给模型的历史有多少 token</b>，
         * 直接等于模型在吐第一个字之前必须做的 prefill 量。
         * <p>
         * 🔴 <b>2026-10-06 修正：24000 这个数是错的，改回 300000。</b>
         * 当时把它从 100000 调到 24000，依据是「首字 4 秒 + 窗口 10 万」，
         * 但<b>那个因果关系是错的</b> —— 后续三次线上日志证明真正的瓶颈另有其人：
         * <ul>
         *   <li>{@code skillResolve=1122ms}：用户技能每次对话都查库（无缓存），而 skillN=1；</li>
         *   <li>{@code CHAT_MEMORY load} 同一请求内<b>查了 3 次</b>同一条会话的 97 条消息。</li>
         * </ul>
         * 两者合计约 3.5 秒，而当时 97 条消息（约 3 万 token）<b>根本没把 24000 的窗口撑满</b>
         * —— 前端「已经聊了很多轮」的提示是误报，模型看到的也只是最近几十轮，
         * 用户却为此被迫开新对话。压窗口治不了首字，只会让人失忆。
         * <p>
         * 现在取 <b>300000</b>：让 {@code contextWindow} 真正起作用
         * （1M 窗口的模型就该能用 100 万，而不是被这里砍到 2.4 万），
         * 同时留一个上限兜底，避免误填了 10M 之类把 prefill 拉到几十秒。
         * <p>
         * 实际生效值 = {@code min(本值, contextWindow - maxOutputTokens)}。
         * 想知道每轮到底发了多少，看日志 {@code CHAT_MEMORY}（条数 / 估算 token / 窗口）。
         * <p>
         * ⚠️ <b>窗口大不等于首字慢</b>，真正影响首字的是「本地前置耗时」。
         * 调大本值前先看 {@code CHAT_PREFLIGHT} 那一行 ——
         * 如果 context/build 段是几百毫秒级别，那窗口再大也只是多花模型的 prefill 时间。
         */
        private int maxTokens = 300000;

        /**
         * 单次对话最多从库里取回多少条历史消息（2026-10-06 新增）。
         * <p>
         * 以前是把整个会话的历史<b>全量</b>拉回来再交给 {@code TokenWindowChatMemory} 裁剪 ——
         * 窗口裁剪发生在 Java 侧，而"拉回来"这一步的传输与反序列化成本<u>已经付掉了</u>。
         * 结果是<b>越聊越慢</b>：聊到第 500 轮时，一次对话要先传 500 条 JSON 才能开始工作。
         * <p>
         * 现在 SQL 层就只取<b>最近</b> N 条（按时间倒序取 N 条再正序返回），
         * 与窗口裁剪的取向一致（窗口本来也是保新丢旧）。
         * 默认值 200 条远大于典型窗口能装下的条数，正常会话不会感觉到差异；
         * 只有超长会话会被截断 —— 而那部分本来也会被窗口裁掉。
         * <p>
         * ⚠️ 这只影响"送给模型的上下文"，<b>不影响已入库的消息</b>，
         * 也不影响前端的历史列表接口（那个走自己的分页查询）。
         */
        private int maxHistoryMessages = 200;

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
         * 默认 {@code 100s}：**刻意小于 {@code nexus.agent.sse.timeout}（1800s）** ——
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

        /**
         * 对**前端**隐藏的工具名（2026-10-07 新增）。
         * <p>
         * 列出的工具照常执行、照常进模型上下文，但：
         * <ul>
         *   <li>不产生 {@code tool_execution} / {@code tool_execution_result} 事件（SSE 静默）；</li>
         *   <li>不出现在 {@code GET /api/history/{sessionId}} 的返回里（刷新页面也不会冒出来）。</li>
         * </ul>
         * 目的是把「内部基建动作」（建沙盒、读写记忆、记日志）从对话流里拿掉 ——
         * 用户看不懂，也不该看到，更不该白占一屏。
         * <p>
         * 🔴 <b>只过滤展示层，绝不动存储层</b>：工具调用与结果必须留在 {@code chat_memory} 里，
         * 因为它们是模型上下文的一部分 —— OpenAI 兼容协议要求 {@code tool_calls}
         * 必须跟对应的 {@code tool_result} 配对，从记忆里删掉会让下一轮请求直接 400。
         * 过滤只发生在 {@code SseResponseConverter} 与 {@code ChatMemoryServiceImpl#toMessageVO} 两处。
         * <p>
         * 匹配时忽略大小写与首尾空格。工具名取自各 {@code @Tool(name = "...")}。
         * 置空（{@code hidden-tools: []}）即全部可见。
         * <p>
         * ⚠️ 按铁律：本列表的<b>唯一事实源是这里的默认值</b>，任何 profile yml 都不要再声明
         * （写了会整份覆盖这里，而代码看起来毫无变化）。护栏见 {@code ToolVisibilityDriftTest}。
         */
        private List<String> hiddenTools = new ArrayList<>(List.of(
                "create_box",            // 建/复用沙盒：纯内部准备动作
                "delete_box",            // 销毁沙盒：用户不关心按量计费的回收
                "search_user_memory",    // 检索用户画像：读了什么记忆不该摊开给用户看
                "save_user_data",        // 存长期记忆：同上，显示出来还会让人不适
                "record_log"             // 记录反馈：纯后台动作
        ));
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
        /** 上下文窗口，默认 65536（2026-10-06 从 256000 下调，理由见 ModelCapabilities） */
        private Integer contextWindow;
        /** 单次最大输出 token，默认 16384（2026-10-06 从 32000 下调） */
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
            /** 上下文窗口；不填时继承供应商级，再没有则 65536（2026-10-06 下调） */
            private Integer contextWindow;
            /** 单次最大输出 token；不填时继承供应商级，再没有则 16384（2026-10-06 下调） */
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
         * ⚠️ <b>2026-10-06 起这个字段的优先级已被角色档位取代</b>（{@code docs/sql/012}）：
         * 注册时 token 额度直接按 {@link #defaultRole} 的档位写入，本字段<b>不再参与注册</b>，
         * 只作为"角色档位缺失时的兜底"。生产请不要再依赖它调额度，
         * 改档位请改 {@code users.role} + {@code users.token_quota}。
         */
        private long defaultQuota = 0;

        /**
         * 新注册用户的默认角色（{@code docs/sql/012}）：{@code NORMAL} / {@code TEST} / {@code VIP}。
         * <p>
         * 默认 {@code NORMAL}。测试环境可以整段配成 {@code TEST} 让所有新账号都拿到测试档位，
         * 免去每次注册后手工改库。无法识别的值会回落到 {@code NORMAL}
         * （见 {@code UserRole#parse}），配错了不会把用户分到奇怪的档位。
         */
        private String defaultRole = "NORMAL";

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

    /**
     * 上传相关的限制（2026-10-07 与 fronted 对齐后新增）。
     * <p>
     * ⚠️ 按铁律：这几个键的<b>唯一事实源是本类的默认值</b>，
     * 任何 profile yml 都不要再声明（写了会盖掉这里，且代码看起来毫无变化）。
     */
    @Data
    public static class Upload {
        /**
         * 单次请求最多允许多少个文件（`POST /api/file`）。
         * <p>
         * 2026-10-07 定为 <b>10</b>：由 fronted 拍板（「要，单次最多 10 个，超限前端直接拦下并提示」）。
         * <p>
         * ⚠️ <b>前端会先拦，但后端必须自己也拦</b> —— 前端的拦截只是体验优化，
         * 不是安全边界：直接调接口、换客户端、脚本批量都绕得过去。
         * 这里是第二道防线，同时也是「契约的机器可读版本」：
         * 前端可以把自己的阈值改成读后端下发的，避免两边各写一个数字后漂移。
         */
        private int maxCount = 10;
    }

    /**
     * 联网搜索（2026-10-07 新增，工具名 {@code web_search}）。
     * <p>
     * 走 <b>Tavily</b>（{@code https://api.tavily.com/search}）—— LLM 生态事实标准，
     * 免费档每月 1000 次，返回 title/url/content 摘要，对模型友好。
     * <p>
     * 🔴 <b>API Key 的读法（2026-10-09 修正）</b>：配置项
     * {@value #API_KEY_PROPERTY} 优先，环境变量 {@code TAVILY_API_KEY} 兜底 ——
     * 由 {@link #API_KEY_EXPRESSION} 这个占位符表达式统一表达，
     * 三个消费方（{@code WebSearchTool} / {@code WebExtractTool} / {@code EffectiveConfigReporter}）
     * <b>都引用同一个常量</b>，避免各写一份后漂移。
     * <p>
     * ⚠️ 曾经这里写的是「Key 只走环境变量，刻意不提供 yml 配置项」，工具直接调
     * {@code System.getenv("TAVILY_API_KEY")}。那个写法有坑（2026-10-09 线上复现）：
     * {@code System.getenv} <b>绕开 Spring</b>，所以写在外部 yml（{@code nexus-override.yml}）、
     * 面板生成的 {@code .env.properties} 里的值<b>一律读不到</b>，用户看到的现象就是
     * 「我明明写到配置文件里了，日志却说没配置」。
     * <p>
     * 这与 {@code JwtUtil} / {@code EncryptorFactory} 在 <b>2026-10-03</b> 踩过的是同一个坑
     * （见 {@code RuntimeSecretInitializer} 的类注释），当时确立的修法就是「密钥走 Spring，
     * 配置项优先 + 同名环境变量兜底」——本次只是让 {@code web_search} 回到同一套写法。
     * <p>
     * <b>仍然不违反铁律 4</b>：本类里没有 Key 的<b>值</b>，只有一个占位符表达式；
     * 值来自仓库之外（外部 yml / 进程环境变量），不会随仓库泄漏。
     * 没配 Key 时整个工具集<b>不注册</b>（模型看不到这个工具，而不是调用了才报错）。
     */
    @Data
    public static class Websearch {

        /**
         * 联网搜索 / 正文提取共用的 API Key 的配置项名。
         * <p>
         * 写成 {@code nexus.agent.websearch.api-key}（而不是仅靠环境变量）是为了让
         * 外部配置文件也能配到它；同名环境变量 {@code TAVILY_API_KEY} 作为兜底。
         */
        public static final String API_KEY_PROPERTY = "nexus.agent.websearch.api-key";

        /**
         * Key 的取值表达式：<b>配置项优先，环境变量兜底</b>。
         * <p>
         * 🔴 默认值（末尾那个空的 {@code :}）不能省：没有它时，两处都没配会让 Spring 抛
         * {@code Could not resolve placeholder}，把「一个可选功能没配」升级成「整个应用起不来」
         * （2026-10-09 就是这么炸的，见 {@code ValueInjectionGuardTest}）。
         * <p>
         * 是 {@code public static final String} 的<b>常量拼接</b>，所以可以直接用在注解里。
         */
        public static final String API_KEY_EXPRESSION =
                "${" + API_KEY_PROPERTY + ":${TAVILY_API_KEY:}}";

        /**
         * 总开关（默认开）。它只控制「配了 Key 就启用」——
         * 没配 Key 时无论本值是什么，工具集都不会注册。
         */
        private boolean enabled = true;

        /**
         * 单次搜索返回的结果条数（1~10）。默认 5：太少覆盖面不够，太多稀释上下文。
         */
        private int maxResults = 5;



        /**
         * 搜索请求超时。搜索应该在几秒内返回 —— 对话链路上工具慢一秒用户就多等一秒。
         */
        private Duration timeout = Duration.ofSeconds(15);

        // ==================== 以下为 /extract（正文提取）的配置 ====================

        /**
         * 单次 {@code web_extract} 最多提交多少个 URL（1~20）。
         * <p>
         * 🔴 默认 5 是刻意对着<b>计费边界</b>定的：Tavily 的 extract 是
         * 「每 5 个<b>成功</b>提取的 URL 扣 1 credit」（advanced 则扣 2）。
         * 取不到 5 个也照样扣 —— 所以一次请求恰好 5 个 URL 时单位成本最低；
         * 第 6 个开始就要多扣一份。20 是 API 侧硬性上限。
         */
        private int extractMaxUrls = 5;

        /**
         * 单个 URL 最多保留多少字符正文。
         * <p>
         * 这是<b>护上下文</b>的闸门，不是省流量的：一个完整网页动辄几万字符，
         * 全量塞进工具结果会直接顶掉记忆窗口。默认 6000（约 1.5 页中文），
         * 够模型读懂主要内容，又不至于把上下文吃光。
         */
        private int extractMaxCharsPerUrl = 6000;

        /**
         * 一次调用返回正文的<b>总</b>字符上限（默认 20000）。
         * 即使每个 URL 都没超单条上限，5 条加起来也能到 3 万 —— 总量必须再卡一道。
         */
        private int extractMaxTotalChars = 20000;

        /**
         * 提取请求超时。basic 的官方默认是 10s、上限 60s；这里取 20s：
         * 抓正文比搜索明显更慢（要真的去取页面），但仍在一次工具调用的容忍范围内。
         */
        private Duration extractTimeout = Duration.ofSeconds(20);

        /**
         * 是否用 advanced 提取深度（能拿到表格和内嵌内容、成功率更高）。
         * <p>
         * ⚠️ 默认 false —— advanced 的单价<b>翻倍</b>（每 5 个 URL 扣 2 credit 而不是 1），
         * 而对话场景绝大多数只是「读一下这篇文章」，basic 够用。
         */
        private boolean extractAdvanced = false;
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
