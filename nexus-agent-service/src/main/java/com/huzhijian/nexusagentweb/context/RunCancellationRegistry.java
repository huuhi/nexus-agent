package com.huzhijian.nexusagentweb.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「停止生成」的运行登记表（2026-10-06 新增）。
 *
 * <h3>为什么需要它</h3>
 * <p>
 * 现在的语义是「<b>断开连接 ≠ 停止任务</b>」：{@code onTimeout} / 前端关网页都只把
 * SSE 标记为断开，TokenStream 继续跑完、消息照常落库 —— 这是 2026-10-03 刻意的修正，
 * 因为那时「一断线就丢弃产出、任务还在烧钱」是最坏的组合。
 * <p>
 * 但这带来另一个问题：<b>用户没法主动叫停</b>。等太久了只能干瞪眼，token 还在烧。
 * 本类提供「显式取消」的能力，与上面的「被动断开」严格分开：
 * <ul>
 *   <li>被动断开（关网页 / 超时）→ 任务继续，落库，不受影响；</li>
 *   <li>主动取消（点「停止生成」）→ 任务真的停下，已生成的内容仍然落库。</li>
 * </ul>
 *
 * <h3>为什么同时登记 sessionId</h3>
 * <p>
 * 前端首帧就能拿到 {@code runId}，用它取消最精确。但也存在「用户手上只有会话」的调用方式
 * （比如会话列表页直接叫停），所以额外维护 sessionId → runId 的反查。
 * <p>
 * ⚠️ 一个会话同一时刻只会有一次运行在跑（前端不会并发发两条 stream），
 * 所以 sessionId 反查是安全的；真出现并发时以<b>后写入的 runId</b> 为准，
 * 取消只会命中正在跑的那次，不会误杀已结束的。
 *
 * <h3>生命周期</h3>
 * <p>
 * {@code register} 在请求线程（拿到 userId 的地方）调用，
 * {@code unregister} 在运行结束时（正常/异常/取消）调用。
 * 容器重启、进程崩溃都会清空，不会残留。
 *
 * @author 胡志坚
 */
@Slf4j
@Component
public class RunCancellationRegistry {

    /** runId → 用户是否已请求停止 */
    private final Map<String, Boolean> cancelled = new ConcurrentHashMap<>();

    /** sessionId → 当前在跑的 runId（用于「只知道会话」时反查） */
    private final Map<String, String> runIdBySession = new ConcurrentHashMap<>();

    /**
     * 登记一次运行。
     *
     * @param runId     本次运行的 trace_id
     * @param sessionId 会话 id
     */
    public void register(String runId, String sessionId) {
        if (runId == null) {
            return;
        }
        cancelled.put(runId, Boolean.FALSE);
        if (sessionId != null) {
            runIdBySession.put(sessionId, runId);
        }
    }

    /**
     * 请求停止某次运行。
     *
     * @return true 表示这次调用把状态从「运行中」改成了「已请求停止」；
     *         false 表示该 runId 不在登记里（已经结束或从未开始）
     */
    public boolean cancel(String runId) {
        if (runId == null) {
            return false;
        }
        // 🔴 必须是 put（覆盖）而不是 putIfAbsent：
        //    putIfAbsent 的语义是「key 不存在才放入」，而 register 已经放了 FALSE，
        //    那样调用会**永远返回旧值 FALSE、并且永远改不了状态** ——
        //    停止功能会静默失效（测试 StopGenerationTest#cancelMarksRun 就是钉这个的）。
        //    put 返回旧值，正好用来判断「是不是从运行中改过来的」。
        Boolean previous = cancelled.put(runId, Boolean.TRUE);
        if (previous == null) {
            // 查无此运行：可能已经结束（或还没开始就调了），别把脏标记留在表里
            cancelled.remove(runId);
            return false;
        }
        log.info("收到停止请求：runId={} 原状态={}", runId, previous);
        return !previous;
    }

    /**
     * 按会话取消（前端只有 sessionId 时的入口）。
     *
     * @return 被取消的 runId；该会话当前没有在跑运行时返回 null
     */
    public String cancelBySession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        String runId = runIdBySession.get(sessionId);
        if (runId == null) {
            return null;
        }
        return cancel(runId) ? runId : null;
    }

    /** 流式回调线程用：本次运行是否已被用户叫停 */
    public boolean isCancelled(String runId) {
        return runId != null && Boolean.TRUE.equals(cancelled.get(runId));
    }

    /**
     * 运行结束，清理登记。
     * <p>必须在所有结束路径上调用（正常完成 / 出错 / 取消），否则 map 会一直长。
     */
    public void unregister(String runId, String sessionId) {
        if (runId == null) {
            return;
        }
        cancelled.remove(runId);
        if (sessionId != null) {
            // 只删自己那条：并发极端情况下别把别人刚登记的 runId 抹掉。
            // ⚠️ remove(key, value) 的 value 为 null 会 NPE，所以 runId 判空后才调。
            runIdBySession.remove(sessionId, runId);
        }
    }

    /** 当前登记中的运行数（监控/排查用） */
    public int activeCount() {
        return cancelled.size();
    }
}
