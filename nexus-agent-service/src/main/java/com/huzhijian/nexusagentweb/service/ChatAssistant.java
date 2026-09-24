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
     * @param contents        用户消息内容（文本 / 文件 / 图片）
     * @param memoryId        会话 ID，同时作为 {@code @ToolMemoryId} 注入给工具
     * @param availableSkills 可用技能清单，注入到系统提示词的 {@code {{availableSkills}}}。
     *                        <p>
     *                        必须显式传入：技能清单是**运行期**才知道的（取决于 skills 目录与请求参数），
     *                        而 {@code @SystemMessage} 是静态文本，只能靠 Mustache 变量填。
     *                        没有它，模型就无法知道有哪些技能、也就无法调用 activate_skill。
     */
    @SystemMessage(CHAT_PROMPT)
    TokenStream chat(@UserMessage List<Content> contents,
                     @V("sessionId") @MemoryId Object memoryId,
                     @V("availableSkills") String availableSkills);


}
