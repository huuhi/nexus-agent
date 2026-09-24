package com.huzhijian.nexusagentweb.converter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 把逐块到达的流式增量**合并成较大的批次**，减少 SSE 帧数（P2-12）。
 * <p>
 * <b>为什么需要</b>：模型是逐 token（更准确说是逐 chunk，通常几个字符）返回的，
 * 原实现每来一块就 {@code emitter.send()} 一次。一次千字回复 = **上千个 SSE 帧**：
 * <ul>
 *   <li>后端：每个帧都要序列化 + 写 socket，Tomcat 侧伴随大量小 flush</li>
 *   <li>前端：EventSource 每个帧都触发一次回调 → 每次都可能触发一次状态更新与重渲染</li>
 * </ul>
 * 两边都被高频小包拖慢 —— 用户感受到的就是"字一个一个蹦、还卡卡的"。
 * <p>
 * <b>策略</b>：满足任一条件就发（先到先发）
 * <ul>
 *   <li>缓冲达到 {@code maxChars} 个字符 —— 保证大段内容及时吐出</li>
 *   <li>距上次发送超过 {@code intervalMillis} 毫秒 —— 保证低速率内容也不会被"憋住"</li>
 * </ul>
 * 另外**类型切换时（思考 ↔ 正文）必须先发掉上一批**：否则前端收到的顺序会错乱
 * （思考内容插到正文后面），拼出来的消息就乱了。
 * <p>
 * <b>为什么单独一个类</b>：{@code SseResponseConverter} 依赖 {@code SseEmitter}，很难单测；
 * 把"何时该发"这个有判断逻辑的部分抽出来，就能用纯单测覆盖边界，
 * 而发送本身只剩一行 {@code emitter.send}。
 * <p>
 * 非线程安全场景下也安全：内部方法均 synchronized（流式回调可能来自不同线程）。
 */
public class SseChunkBuffer {

    /**
     * 一批待发送的增量。
     *
     * @param type 消息类型（{@code MessageType.THINK} / {@code CONTENT} 的枚举名）
     * @param text 合并后的文本
     */
    public record Batch(String type, String text) {
    }

    /** 缓冲达到多少字符就立即发送 */
    private final int maxChars;
    /** 距上次发送超过多少毫秒就立即发送（低速率内容的兜底） */
    private final long intervalMillis;

    private final StringBuilder pending = new StringBuilder();
    private String pendingType;
    private long lastFlushAt = System.currentTimeMillis();

    public SseChunkBuffer(int maxChars, long intervalMillis) {
        // 防呆：配置成 0 或负数会导致每块都发（等于没优化），这里兜到安全值
        this.maxChars = maxChars > 0 ? maxChars : 200;
        this.intervalMillis = intervalMillis > 0 ? intervalMillis : 60L;
    }

    /**
     * 追加一段增量。
     *
     * @return 本次**应当立即发送**的批次；未到发送时机时返回 {@code null}
     */
    public synchronized Batch append(String type, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (pendingType != null && !pendingType.equals(type)) {
            // 类型切换：先把上一批发掉（返回它），新内容留在缓冲里等下一次判断
            Batch flushed = drainLocked();
            pendingType = type;
            pending.append(text);
            return flushed;
        }
        pendingType = type;
        pending.append(text);

        boolean reachedChars = pending.length() >= maxChars;
        boolean reachedTime = System.currentTimeMillis() - lastFlushAt >= intervalMillis;
        return (reachedChars || reachedTime) ? drainLocked() : null;
    }

    /**
     * 强制取出剩余内容。
     * <p>
     * ⚠️ **必须在这些时机调用**，否则前端会看到顺序错乱或丢内容：
     * 发送工具事件前、推送产物事件前、结束、报错。
     *
     * @return 剩余批次；缓冲为空时返回 {@code null}
     */
    public synchronized Batch drain() {
        return drainLocked();
    }

    private Batch drainLocked() {
        if (pending.length() == 0) {
            return null;
        }
        Batch batch = new Batch(pendingType, pending.toString());
        pending.setLength(0);
        pendingType = null;
        lastFlushAt = System.currentTimeMillis();
        return batch;
    }

    /** 是否有未发出的内容（供排查/测试观察） */
    public synchronized boolean hasPending() {
        return pending.length() > 0;
    }
}
