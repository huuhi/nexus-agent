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

    /**
     * 本次对话的「上下文用量」快照（2026-10-06 新增）。
     * <p>
     * 里面装着<b>本次的记忆窗口</b>与<b>实际加载了多少历史</b>，会随收尾事件下发给前端，
     * 让「要不要建议用户开新会话」有真实依据 —— 此前前端只能数消息条数，
     * 而条数与真实占用完全不成正比（一轮带工具调用的对话能顶几十轮闲聊），
     * 于是出现「明明还能聊却提示新建对话」。
     * <p>
     * ⚠️ 这是个<b>可变</b>对象：历史由 LangChain4j 在 {@code AiServices.chat()} 内部加载，
     * 晚于本对象的构造，所以这里拿的是「壳」，值在加载时回填。
     * 读取时机必须在 {@code chat()} 返回<b>之后</b>。
     */
    private ContextUsage contextUsage;
}
