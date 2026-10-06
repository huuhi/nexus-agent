package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/16
 * 说明:
 */
public record ChatDTO(@NotNull(message = "发送的消息不能为空！") List<ChatUserMessage> messages,
                      String sessionId,
                      List<String> skills,
                      List<Long> MCPs,
                      ModelDTO model,
                      /**
                       * 是否启用乐享知识库检索。
                       * <p>
                       * ⚠️ 原先还有一个 {@code enableRag}（本地 pgvector 知识库），已于 2026-10-04
                       * 随本地知识库一起下线 —— 现在知识库检索只有乐享这一条路。
                       */
                      boolean enableLexiangRag,
                      /**
                       * 🔴 <b>「重新生成」：本次要基于哪一条回答重新答（2026-10-06 新增）。</b>
                       * <p>
                       * 取自历史接口 {@code GET /api/history/{sessionId}} 返回的
                       * {@code data[].id}（<b>字符串</b>形态的雪花 ID，原样回传即可）。
                       * <p>
                       * <b>不传 = 正常发问</b>，行为与本字段出现前完全一致。
                       * <ul>
                       *   <li>传了 → 本次走「重新生成」：<b>不重复写用户提问</b>（问题已经问过了），
                       *       并把「比它更新的、当前生效的回答」标记为被本次的新回答替代；</li>
                       *   <li>这些被标记的回答仍会返回给前端做 n/n 切换，
                       *       但<b>不再进入模型上下文</b> —— 否则模型会同时看到两版回答，
                       *       既白烧 token 又让它困惑，而且用户切回旧版接着聊时，
                       *       模型记得的仍是另一版，切换就形同虚设。</li>
                       * </ul>
                       * ⚠️ 用<b>字符串</b>比较，不要转成数字（19 位超出 JS 的安全整数范围）。
                       */
                      String regenerateFromMessageId) {

    /** 便捷判断：本次是不是「重新生成」 */
    public boolean isRegenerate() {
        return regenerateFromMessageId != null && !regenerateFromMessageId.isBlank();
    }
}
