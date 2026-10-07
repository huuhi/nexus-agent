package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.properties.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具调用的**展示层可见性**判定（2026-10-07 新增）。
 * <p>
 * 一句话：决定「这次工具调用要不要出现在用户面前」。
 * 判定依据只有工具名，来自 {@code nexus.agent.tools.hidden-tools}
 * （{@link AgentProperties.Tools#getHiddenTools()}）。
 *
 * <h2>🔴 这是"展示层过滤"，不是"执行层过滤"</h2>
 * <ol>
 *   <li><b>工具照常执行</b> —— 建沙盒、读写记忆这些该做还得做；</li>
 *   <li><b>结果照常进模型上下文</b> —— {@code chat_memory} 里那条
 *       {@code ToolExecutionResultMessage} 必须留着。OpenAI 兼容协议要求
 *       {@code tool_calls} 后面必须跟对应 id 的 {@code tool_result}，
 *       从记忆里删掉任何一半，下一轮请求直接 400
 *       （{@code An assistant message with 'tool_calls' must be followed by tool messages...}）；</li>
 *   <li><b>只是不给前端</b> —— 过滤点是两个：
 *       {@code SseResponseConverter}（实时流）与
 *       {@code ChatMemoryServiceImpl#toMessageVO}（历史接口）。</li>
 * </ol>
 * 所以这里<b>永远不要</b>被拿去拦 {@code ToolRegistry} 的选工具逻辑，
 * 也不要拿去改 {@code ChatMemoryStore} 的读写 —— 那会把模型搞坏。
 *
 * <h2>为什么做成 Bean 而不是静态工具类</h2>
 * 判定要读配置，而配置是 Spring 管的；做成 Bean 后
 * SSE 转换器与历史服务注入同一个实例，不会各读一份导致口径漂移。
 * 集合在构造时冻结（不可变），运行期只读，没有并发问题。
 */
@Component
@Slf4j
public class ToolVisibility {

    /** 隐藏清单（小写、已 trim；构造后不可变） */
    private final Set<String> hidden;

    public ToolVisibility(AgentProperties properties) {
        List<String> raw = properties.getTools() == null ? null : properties.getTools().getHiddenTools();
        if (raw == null || raw.isEmpty()) {
            this.hidden = Collections.emptySet();
        } else {
            Set<String> normalized = new LinkedHashSet<>();
            for (String name : raw) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                normalized.add(normalize(name));
            }
            this.hidden = Collections.unmodifiableSet(normalized);
        }
        // 启动快照：这类"看不见的配置"最容易变成"以为生效了其实没有"，打出来一眼可查
        if (hidden.isEmpty()) {
            log.info("工具可见性：未配置隐藏工具，所有工具调用都会下发给前端");
        } else {
            log.info("工具可见性：以下 {} 个工具对前端隐藏（照常执行，只是不推事件、不进历史）: {}",
                    hidden.size(), hidden);
        }
    }

    /**
     * 该工具是否应对前端隐藏。
     *
     * @param toolName 工具名（{@code @Tool(name=...)}）；为 null / 空白时按「不隐藏」处理
     *                 —— 拿不到名字就放行，宁可多显示也不能把整条消息吞掉
     * @return true = 不发给前端
     */
    public boolean isHidden(String toolName) {
        if (toolName == null || toolName.isBlank() || hidden.isEmpty()) {
            return false;
        }
        return hidden.contains(normalize(toolName));
    }

    /** 当前生效的隐藏清单（只读，供日志与诊断用） */
    public Set<String> hiddenTools() {
        return hidden;
    }

    private static String normalize(String toolName) {
        return toolName.trim().toLowerCase(Locale.ROOT);
    }
}
