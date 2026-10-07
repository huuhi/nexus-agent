package com.huzhijian.nexusagentweb.context;

/**
 * 一次对话的「上下文用量」快照（2026-10-06 新增）。
 * <p>
 * <b>为什么需要它</b>：前端此前判断「这个会话是不是聊太久了，该建议用户开新会话」，
 * 只能靠<b>自己数消息条数</b>（历史做法：超过 12 条就提示）。而条数与真实占用完全不成比例 ——
 * 一轮带工具调用的 agent 对话（沙盒输出、检索结果）能顶几十轮纯文本闲聊。
 * 结果是「明明还能聊，却提示新建对话」（2026-10-06 用户反馈的原话：
 * 「现在很多模型都是 1M，对话几次就让我 new 窗口，怎么可能那么快」）。
 * <p>
 * 有了它，前端可以拿 <b>服务端口径的真实用量</b> 做判断，而不是猜。
 * <p>
 * ⚠️ <b>为什么这个类是可变的</b>：历史消息的加载发生在
 * {@code AiServices.chat()} 内部（LangChain4j 自己触发 {@code ChatMemoryStore.getMessages}），
 * 晚于 {@link ChatContext} 的构造。所以只能先建一个空壳，加载时再回填。
 * 字段用 {@code volatile}：写入方是记忆存储的读取线程，读取方可能是 SSE 收尾线程。
 * <p>
 * ⚠️ <b>{@code loadedTokens} 是「加载量」不是「发送量」</b>：
 * 它是本次从库里取回的历史消息估算 token 数，发生在 {@code TokenWindowChatMemory}
 * 裁剪<b>之前</b>。裁剪后真正发给模型的会更少。
 * 这个口径对前端反而更合适 —— 用户关心的「我这个会话积累了多少上下文」正是加载量，
 * 用它判断「要不要建议开新会话」比用裁剪后的发送量更符合直觉
 * （裁剪后永远贴着窗口，看不出趋势）。
 */
public class ContextUsage {

    private final int window;

    private volatile int loadedMsgs;
    private volatile int loadedTokens;

    /**
     * @param window 本次对话的记忆窗口（token）＝
     *               {@code min(nexus.agent.memory.max-tokens, 模型的 contextWindow - maxOutputTokens)}
     */
    public ContextUsage(int window) {
        this.window = window;
    }

    /**
     * 回填本次实际加载的历史规模。可能被多次调用（同一 run 内记忆会被读多次），
     * 取<b>最后一次</b>的值 —— 那时的条数才是真正参与本轮对话的那份。
     */
    public void recordLoaded(int msgs, int tokens) {
        this.loadedMsgs = msgs;
        this.loadedTokens = tokens;
    }

    public int window() {
        return window;
    }

    /** 本次加载的历史消息条数（受 {@code nexus.agent.memory.max-history-messages} 截断） */
    public int loadedMsgs() {
        return loadedMsgs;
    }

    /** 本次加载的历史消息估算 token 数（裁剪前） */
    public int loadedTokens() {
        return loadedTokens;
    }

    /**
     * 已用比例（0~1，可能 > 1 —— 加载量本来就允许超出窗口，超出部分会被裁掉）。
     * <p>
     * ⚠️ 超过 1.0 就意味着<b>本轮已经开始丢更早的历史</b>，是前端提示「建议开新会话」
     * 最合适的触发点；窗口为 0（配置异常）时返回 0，不制造 {@code NaN}。
     */
    public double ratio() {
        if (window <= 0) {
            return 0;
        }
        return (double) loadedTokens / window;
    }
}
