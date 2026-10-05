package com.huzhijian.nexusagentweb.context;


import com.huzhijian.nexusagentweb.service.ChatAssistant;
import dev.langchain4j.skills.Skills;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明: 一次对话的运行期上下文（不可变产物，由 ChatContextFactory 组装）。
 */
@Getter
@Builder
public class ChatContext {
    private ChatAssistant chatAssistant;
    private String sessionId;
    private boolean isNewSession;

    /**
     * 本次请求**选择了但连不上**的 MCP 服务名（P2-9）。
     * <p>
     * 用于在系统提示词里告知模型「该能力当前不可用」—— 否则模型只会说"我没有这个能力"，
     * 用户也分不清是"没配"还是"配了但连不上"。
     */
    @Builder.Default
    private List<String> mcpUnavailable = List.of();

    /**
     * 本次对话启用的技能集合（可能为 null，表示「没有可用技能」）。
     * <p>
     * 2026-10-05：随上下文带出来，供 {@code ChatServiceImpl} 组装提示词时复用 ——
     * 以前那里会再解析一次技能清单，等于每次对话多查一遍用户技能表。
     */
    private Skills skills;

    /**
     * {@link #skills} 对应的「给模型看的清单文本」。
     * <p>
     * 由 {@code SkillLoader.formatResolved} 生成，null 时是一句明确的
     * 「当前没有可用的技能」，避免提示词里出现空占位。
     */
    private String skillsText;
}
