package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.handler.GlobalExceptionHandler;
import com.huzhijian.nexusagentweb.vo.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 业务异常必须走业务出口，不能掉兜底 500（2026-10-05）。
 * <p>
 * <b>线上现象</b>：{@code GET /api/lexiang/teams} 一直返回 <b>500</b>，
 * 前端只看到一句「Internal Server Error」，完全不知道是没配凭证、AppKey 错了、
 * 还是撞了乐享的限频。
 * <p>
 * <b>根因</b>：乐享链路（{@code LexiangClient} / {@code LexiangTokenProvider} /
 * {@code LexiangServiceImpl}）抛的全是 {@code IllegalStateException}，
 * 而 {@code GlobalExceptionHandler} 里<b>没有为它注册分支</b> ——
 * 于是全部掉进 {@code handleUnexpected}，变成 500 + 「系统内部错误」。
 * 换句话说：「用户配错了」和「服务真的挂了」在前端长得一模一样，只能翻服务端日志才能区分。
 * <p>
 * 这正是 `AGENTS.md` 里那条铁律的翻车现场：
 * <b>新异常类型必须注册到 GlobalExceptionHandler，否则掉兜底 500。</b>
 * <p>
 * 本用例按 AGENTS.md §3.1 的「配置/契约层」思路编写：它盯的是
 * **异常 → 响应码的这个映射关系本身**，而不是某段业务逻辑 ——
 * 这类映射错了，纯 mock 单测是一点都抓不到的（业务代码全绿，接口照样 500）。
 */
@DisplayName("全局异常处理 —— 业务异常不能掉兜底 500")
class GlobalExceptionHandlerMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("IllegalStateException（乐享链路全用它）：变成 code=1 + 原始中文提示，不是 500")
    void illegalStateBecomesReadableBusinessError() {
        Result r = handler.handleIllegalState(new IllegalStateException(
                "尚未接入乐享知识库：请先在设置里填写 AppKey 与 AppSecret。"));

        assertEquals(1, r.getCode(), "必须走业务异常出口（HTTP 200 + code=1），不能是兜底 500");
        assertEquals("尚未接入乐享知识库：请先在设置里填写 AppKey 与 AppSecret。", r.getMsg(),
                "真实原因必须原样透给用户 —— 否则用户不知道该去配凭证");
    }

    @Test
    @DisplayName("乐享的几种典型失败：都要原样透出（AppKey 无效 / AppSecret 错 / 限频）")
    void lexiangFailuresKeepTheirMessages() {
        assertEquals("AppKey 无效，请到乐享【开发】→【接口凭证管理】核对。",
                handler.handleIllegalState(new IllegalStateException(
                        "AppKey 无效，请到乐享【开发】→【接口凭证管理】核对。")).getMsg());
        assertEquals("AppSecret 错误，请重新填写。",
                handler.handleIllegalState(new IllegalStateException("AppSecret 错误，请重新填写。")).getMsg());
        assertEquals("获取凭证过于频繁（乐享限制 20 次/10 分钟），请稍后再试。",
                handler.handleIllegalState(new IllegalStateException(
                        "获取凭证过于频繁（乐享限制 20 次/10 分钟），请稍后再试。")).getMsg());
    }

    @Test
    @DisplayName("无参 IllegalStateException（message 为 null）：给兜底文案，不能把 \"null\" 显示给用户")
    void blankIllegalStateMessageGetsFallback() {
        Result r = handler.handleIllegalState(new IllegalStateException());

        assertNotNull(r.getMsg(), "不能把 null 透出去");
        assertFalse(r.getMsg().isBlank());
        assertFalse("null".equals(r.getMsg()), "字面量 \"null\" 出现在界面上是最难排查的那种 bug");
    }

    @Test
    @DisplayName("IllegalArgumentException（如乐享知识库列表缺 teamId）：同样走业务出口")
    void illegalArgumentBecomesReadableBusinessError() {
        Result r = handler.handleIllegalArgument(new IllegalArgumentException(
                "乐享知识库列表需要 teamId：请先在团队列表里选一个团队。"));

        assertEquals(1, r.getCode());
        assertEquals("乐享知识库列表需要 teamId：请先在团队列表里选一个团队。", r.getMsg());
    }

    @Test
    @DisplayName("ParserFileException（技能包解析失败）：注册后不再掉兜底 500")
    void parserFileExceptionIsHandled() {
        Result r = handler.handleParserFile(new ParserFileException(
                "技能包解析失败：缺少 SKILL.md"));

        assertEquals(1, r.getCode());
        assertEquals("技能包解析失败：缺少 SKILL.md", r.getMsg());
    }
}
