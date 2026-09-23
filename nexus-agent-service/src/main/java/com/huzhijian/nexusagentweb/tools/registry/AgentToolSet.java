package com.huzhijian.nexusagentweb.tools.registry;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/23
 * 说明: 一组交给 AI 使用的工具（一个工具类 = 一个工具集）。
 * <p>
 * <b>为什么要这个接口：</b>在此之前，「有哪些工具」这件事是硬编码在
 * {@code ChatContextFactory} 里的（逐个注入 + 逐个 {@code .tools(...)}），
 * 每加一个工具都要改工厂、改构造参数，而且没法按条件开关。
 * <p>
 * 现在改为：工具类自己声明「我是谁、什么时候启用」，{@link ToolRegistry}
 * 通过 Spring 收集全部实现并统一解析。**新增工具只需新建一个实现该接口的 @Component，
 * 不需要再动 ChatContextFactory。**
 * <p>
 * 注意：只覆盖项目内置的 @Tool 工具。MCP 是另一套机制（外部 toolProvider），
 * 不在这里管理，见 {@code ChatContextFactory}。
 */
public interface AgentToolSet {

    /**
     * 唯一标识，用于日志排查与后续的「能力清单」接口。建议用简短的英文小写，如 box / rag。
     */
    String key();

    /**
     * 人类可读说明，供前端展示「这个 Agent 有哪些能力」时使用。
     */
    default String description() {
        return "";
    }

    /**
     * 交给 LangChain4j {@code AiServices.tools(...)} 的对象。
     * 持有 @Tool 方法的 bean 通常就是 this 本身，所以默认返回自身。
     */
    default Object[] toolObjects() {
        return new Object[]{this};
    }

    /**
     * 本次运行是否启用该工具集。默认常驻。
     */
    default boolean enabled(ToolSelection selection) {
        return true;
    }
}
