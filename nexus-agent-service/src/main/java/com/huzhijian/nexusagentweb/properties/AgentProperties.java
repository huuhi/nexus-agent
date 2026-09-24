package com.huzhijian.nexusagentweb.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    @Data
    public static class Sse {
        /**
         * SSE 连接超时。**必须大于最慢一次模型调用的耗时**，否则复杂任务会被提前掐断。
         * 默认 120 秒。
         */
        private Duration timeout = Duration.ofSeconds(120);
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
}
