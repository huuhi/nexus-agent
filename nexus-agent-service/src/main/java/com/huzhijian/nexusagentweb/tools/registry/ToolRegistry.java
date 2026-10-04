package com.huzhijian.nexusagentweb.tools.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 工具注册表。Spring 自动收集所有 {@link AgentToolSet} 实现，按本次运行的选择解析出工具列表。
 * <p>
 * 解决的问题：工具原来硬编码在 {@code ChatContextFactory} 里（逐个注入 + 逐个注册），
 * 新增工具必须改工厂；开关条件（如 enableLexiangRag）也散落在业务代码里。
 * 现在「有哪些工具」由各工具类自我声明，工厂只调一次 {@link #resolve(ToolSelection)}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    /**
     * Spring 会把所有 {@link AgentToolSet} 的实现注入进来。
     * 新增工具类只需加 @Component 并实现该接口，无需修改本类或 ChatContextFactory。
     */
    private final List<AgentToolSet> toolSets;

    /**
     * 解析本次运行应启用的工具对象列表。
     */
    public List<Object> resolve(ToolSelection selection) {
        List<Object> resolved = new ArrayList<>();
        for (AgentToolSet toolSet : toolSets) {
            boolean on = toolSet.enabled(selection);
            log.debug("工具集 [{}] {}", toolSet.key(), on ? "启用" : "跳过");
            if (on) {
                resolved.addAll(Arrays.asList(toolSet.toolObjects()));
            }
        }
        if (resolved.isEmpty()) {
            // 理论上不可能：常驻工具（沙盒/日志/记忆）默认恒为启用
            log.warn("本次运行没有解析出任何工具，请检查 AgentToolSet 的实现是否被 Spring 扫描到");
        }
        return resolved;
    }

    /**
     * 当前注册的全部工具集标识，便于排查「某个工具为什么没生效」，
     * 后续也可作为对外「能力清单」接口的数据源。
     */
    public List<String> keys() {
        return toolSets.stream().map(AgentToolSet::key).toList();
    }

    /**
     * 全部工具集及其说明。
     */
    public List<String> describeAll() {
        return toolSets.stream()
                .map(t -> t.key() + (t.description().isEmpty() ? "" : "：" + t.description()))
                .toList();
    }
}
