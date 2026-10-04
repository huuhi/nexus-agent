package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolRegistry;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry 的纯单元测试：验证「按选择过滤」这个核心行为。
 * <p>
 * 这里用假的 AgentToolSet 实现，不依赖 Spring 容器。
 */
class ToolRegistryTest {

    /** 恒启用的工具集，key=always */
    private static class AlwaysToolSet implements AgentToolSet {
        @Override
        public String key() {
            return "always";
        }

        @Override
        public String description() {
            return "常驻工具";
        }
    }

    /** 只在按需开关打开时启用的工具集，key=on_demand */
    private static class OnDemandToolSet implements AgentToolSet {
        @Override
        public String key() {
            return "on_demand";
        }

        @Override
        public String description() {
            return "按需工具";
        }

        @Override
        public boolean enabled(ToolSelection selection) {
            return selection.lexiangRagEnabled();
        }
    }

    private final ToolRegistry registry = new ToolRegistry(List.of(new AlwaysToolSet(), new OnDemandToolSet()));

    @Test
    @DisplayName("按需开关关闭时只解析出常驻工具")
    void onDemandOffOnlyResolvesAlwaysOnTools() {
        List<Object> resolved = registry.resolve(ToolSelection.none());

        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0) instanceof AlwaysToolSet);
    }

    @Test
    @DisplayName("按需开关打开时两个工具集都被解析出来")
    void onDemandOnResolvesBoth() {
        List<Object> resolved = registry.resolve(new ToolSelection(true));

        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().anyMatch(t -> t instanceof OnDemandToolSet));
    }

    @Test
    @DisplayName("keys() 列出全部已注册工具集（含当前未启用的）")
    void keysListsAllRegistered() {
        assertEquals(List.of("always", "on_demand"), registry.keys());
        assertFalse(registry.keys().isEmpty());
    }

    @Test
    @DisplayName("describeAll() 带上说明，便于后续对外暴露能力清单")
    void describeAllIncludesDescription() {
        List<String> described = registry.describeAll();

        assertEquals(2, described.size());
        assertTrue(described.get(0).startsWith("always："));
    }
}
