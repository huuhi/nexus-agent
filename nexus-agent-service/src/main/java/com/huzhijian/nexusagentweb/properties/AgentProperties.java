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
}
