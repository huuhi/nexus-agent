package com.huzhijian.nexusagentweb.tools.registry;

import com.huzhijian.nexusagentweb.dto.ChatDTO;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 本次对话需要哪些工具。
 * <p>
 * 由请求参数推导而来，交给 {@link AgentToolSet#enabled(ToolSelection)} 判断。
 * 以后要加「按用户/按场景禁用某类工具」时，只需在这里加字段，不必改各个工具类。
 *
 * @param ragEnabled 是否启用知识库检索（对应 {@code ChatDTO.enableRag}）
 */
public record ToolSelection(boolean ragEnabled) {

    public static ToolSelection from(ChatDTO chatDTO) {
        return new ToolSelection(chatDTO.enableRag());
    }

    /**
     * 不启用任何可选工具（仅常驻工具）。
     */
    public static ToolSelection none() {
        return new ToolSelection(false);
    }
}
