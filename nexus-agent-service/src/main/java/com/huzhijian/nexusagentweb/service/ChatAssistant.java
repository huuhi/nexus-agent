package com.huzhijian.nexusagentweb.service;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.*;

import java.util.List;

import static com.huzhijian.nexusagentweb.content.ModelSystemContent.CHAT_PROMPT;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public interface ChatAssistant {

    /**
     * @param contents            用户消息内容（文本 / 文件 / 图片）
     * @param memoryId            会话 ID，同时作为 {@code @ToolMemoryId} 注入给工具
     * @param runtimeCapabilities 本次运行的**运行时能力说明**，注入系统提示词的
     *                            {@code {{runtimeCapabilities}}}：包含可用技能清单，
     *                            以及「选了但连不上」的 MCP 服务名（P2-9）。
     *                            <p>
     *                            必须显式传入：这些信息都是**运行期**才知道的
     *                            （取决于 skills 目录、请求参数、以及各 MCP 服务的连通性），
     *                            而 {@code @SystemMessage} 是静态文本，只能靠 Mustache 变量填。
     *                            没有它，模型既不知道有哪些技能（无法调用 activate_skill），
     *                            也不知道哪些能力当前不可用（会把"配了但连不上"说成"我没有这个能力"）。
     */
    @SystemMessage(CHAT_PROMPT)
    TokenStream chat(@UserMessage List<Content> contents,
                     @V("sessionId") @MemoryId Object memoryId,
                     @V("runtimeCapabilities") String runtimeCapabilities);


}
