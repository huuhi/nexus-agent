package com.huzhijian.nexusagentweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huzhijian.nexusagentweb.config.JacksonConfig;
import com.huzhijian.nexusagentweb.converter.SseResponseConverter;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import com.huzhijian.nexusagentweb.vo.QuotaVO;
import com.huzhijian.nexusagentweb.vo.Result;
import com.huzhijian.nexusagentweb.vo.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 2026-10-08「`ttfbMs` 下发成字符串」事故的防复发护栏。
 * <p>
 * <b>事故经过</b>：frontend 在真实链路上（真实账号 + 真实后端）实测抓到 finish 帧原文：
 * <pre>{@code {"status":"DONE","ttfbMs":"6667","contextWindow":300000,"contextUsed":10,…}}</pre>
 * —— {@code ttfbMs} 带引号，而 {@code context*} 四个字段都是裸数字。契约承诺的是 number。
 * <p>
 * <b>根因</b>：{@code JacksonConfig}（2026-10-05，为雪花 ID 精度）把<b>所有</b>
 * {@code Long}/{@code long} 序列化成字符串。{@code SseResponseConverter.withTtfb} 往 data 里
 * 放的恰好是 {@code Long}，于是被那条全局规则误伤；{@code context*} 是
 * {@code int}/{@code double}，天然是 number。**差别只在类型，不在有没有下发。**
 * <p>
 * 🔴 <b>为什么原来的测试全绿却漏了它</b>：{@code FrontendTelemetryTest} 断言的是
 * {@code data.get("ttfbMs")} 这个 <b>Map 里的 Java 对象</b>（{@code assertEquals(1820L, …)}），
 * 而序列化是之后才发生的。**测了值、没测形态**，于是 {@code Long} 变字符串这条链
 * 在断言里完全不可见。
 * <p>
 * 所以本类刻意**真的跑一遍 ObjectMapper**（并且用的就是
 * {@code JacksonConfig#longToStringCustomizer}，不是裸 mapper）——
 * 只有 JSON 文本才有"有没有引号"这个概念。
 * <p>
 * 两条断言方向相反、缺一不可：
 * <ol>
 *   <li>{@code seq} / {@code ttfbMs} 这类<b>计量值</b>必须是 <b>number</b>
 *       （前端要拿它们做算术：跳号检测、延迟对比）；</li>
 *   <li>{@code id} 这类<b>雪花主键</b>必须仍然是 <b>string</b>
 *       —— 这条守的是 {@code JacksonConfig} 的原始意图，防止有人为了修 ①
 *       把全局的 Long→String 直接删掉，那样会立刻把「文件删不掉」那个老 bug 放回来。</li>
 * </ol>
 */
@DisplayName("JSON 数字形态（计量值必须是 number，雪花主键必须仍是 string）")
class NumberFieldSerializationTest {

    private static final String RUN_ID = "run-num";
    private static final String SESSION_ID = "sess-num";

    /**
     * 与应用运行时**同源**的 ObjectMapper：沿用 {@code JacksonConfig} 的 customizer，
     * 而不是 {@code new ObjectMapper()}。
     * <p>
     * ⚠️ 这一句是本类有没有意义的分水岭 —— 用裸 mapper 的话，Long 本来就是 number，
     * 测试会全绿，而线上照旧带引号。
     */
    private static ObjectMapper contractMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("seq 必须是 JSON number（前端拿它做跳号检测，字符串会让检测静默失效）")
    void seqIsJsonNumber() throws Exception {
        Recorder r = new Recorder();
        r.writeContent("正文");
        r.finish();

        ObjectMapper mapper = contractMapper();
        assertTrue(r.frames.size() >= 2, "至少要跑出 run + finish 两帧，实际 " + r.frames.size());

        for (SseEvent frame : r.frames) {
            JsonNode json = mapper.readTree(mapper.writeValueAsString(frame));
            JsonNode seq = json.get("seq");
            assertNotNull(seq, "信封必须有 seq");
            assertTrue(seq.isNumber(),
                    "seq 下发成了 " + seq + "（isTextual=" + seq.isTextual() + "），不是裸数字。"
                            + "契约承诺它是 number，前端用 `seq - prev !== 1` 判丢帧 ——"
                            + " 字符串参与算术会静默得出 NaN，检测永远不报警。"
                            + " 根因是全局 Long→String 序列化，seq 声明成 long 就会被误伤，"
                            + " 声明成 int 即可（见 SseEvent.seq 的注释）。");
        }
    }

    @Test
    @DisplayName("ttfbMs 必须是 JSON number（frontend 实测抓到过 \"6667\" 这种形态）")
    void ttfbMsIsJsonNumber() throws Exception {
        Recorder r = new Recorder();
        r.markTtfb(6667);
        r.writeContent("正文");
        r.finish();

        SseEvent finish = lastOf(r, "finish");
        JsonNode data = contractMapper().readTree(
                contractMapper().writeValueAsString(finish)).get("data");

        JsonNode ttfb = data.get("ttfbMs");
        assertNotNull(ttfb, "markTtfb 调过之后，finish 必须带 ttfbMs");
        assertTrue(ttfb.isNumber(),
                "ttfbMs 下发成了 " + ttfb + "（isTextual=" + ttfb.isTextual() + "）。"
                        + " frontend 在真实链路上抓到的原文就是 {\"ttfbMs\":\"6667\"} ——"
                        + " 前端 `data.ttfbMs ?? 本地测量` 会把字符串混进算术。"
                        + " 修法：withTtfb 里写成 int，别直接放 Long（见该方法注释）。");
        assertEquals(6667, ttfb.asInt(), "数值本身不能变，只是形态从字符串回到数字");
    }

    @Test
    @DisplayName("同一帧里 context* 是 number、雪花 id 是 string —— 两种形态各就各位")
    void mixedShapeInOneFrame() throws Exception {
        MessageVO vo = MessageVO.builder()
                .id(2107069112529358849L)   // 19 位雪花 ID：必须带引号
                .supersededBy(null)
                .build();
        SseEvent frame = SseEvent.builder().seq(3).runId(RUN_ID).event("message").data(vo).build();

        JsonNode json = contractMapper().readTree(contractMapper().writeValueAsString(frame));

        assertTrue(json.get("seq").isNumber(), "seq 是计量序号 → number");
        JsonNode id = json.get("data").get("id");
        assertTrue(id.isTextual(),
                "雪花主键 id 必须是字符串 —— JS 的 number 只能精确表示 16 位整数，"
                        + " 19 位会在 JSON.parse 时被抹掉低位，删文件/切版本全部落空"
                        + "（2026-10-05 那个「每一行都删不掉」的真凶）。"
                        + " 这条与 seq 的断言方向相反，两条一起看才说明"
                        + "「不是 Long→String 整条规则错了，而是它误伤到了计量字段」。");
        assertEquals("2107069112529358849", id.asText());
    }

    @Test
    @DisplayName("契约声明为 number 的计量字段（fileSize / total / 六项配额）都必须是 JSON number")
    void contractNumberFieldsAreJsonNumbers() throws Exception {
        ObjectMapper mapper = contractMapper();

        // —— fileSize：文件列表 / 附件。前端要格式化体积。id 必须仍是字符串（同一对象里两态并存）
        SysFile file = SysFile.builder().id(2107069112529358849L).fileSize(20_971_520L).build();
        JsonNode fileJson = mapper.readTree(mapper.writeValueAsString(file));
        assertTrue(fileJson.get("fileSize").isNumber(),
                "fileSize 要 number（前端算体积），实际 " + fileJson.get("fileSize"));
        assertTrue(fileJson.get("id").isTextual(),
                "同一个对象里 id 必须仍是字符串 —— 这条与 fileSize 相反，"
                        + "两条一起看才说明「不是整条 Long 规则错了，而是它误伤了计量字段」");

        // —— Result.total：分页。前端要做分页算术
        JsonNode resultJson = mapper.readTree(mapper.writeValueAsString(
                new Result(0, "ok", null, 42L)));
        assertTrue(resultJson.get("total").isNumber(),
                "Result.total 要 number（分页算术），实际 " + resultJson.get("total"));

        // —— QuotaVO：token 与文件配额，前端要做「已用 X / 上限 Y」
        JsonNode quotaJson = mapper.readTree(mapper.writeValueAsString(QuotaVO.builder()
                .quota(10_000_000L).used(1_000_000L).remaining(9_000_000L)
                .fileQuota(100L).fileUsed(3L).fileRemaining(97L)
                .build()));
        for (String field : List.of("quota", "used", "remaining",
                "fileQuota", "fileUsed", "fileRemaining")) {
            assertNotNull(quotaJson.get(field), "配额响应里少了 " + field);
            assertTrue(quotaJson.get(field).isNumber(),
                    field + " 要 number（前端要用它算剩余量），实际 " + quotaJson.get(field));
        }
    }

    /**
     * Mixin 是按<b>方法名</b>匹配属性的 —— 名字写错（或实体改了 getter 名）
     * <b>不会报错</b>，只是那条覆盖静默失效，字段又变回字符串。
     * <p>
     * 所以这里反向检查一次：{@code JacksonConfig} 里每个 mixin 声明的 getter，
     * 在目标类上都必须真实存在。写错名字会在测试里立刻暴露，而不是等前端来报。
     */
    @Test
    @DisplayName("mixin 声明的属性必须在目标类上真实存在（写错名会静默失效）")
    void mixinDeclaredPropertiesExistOnTargets() {
        assertMixinMatches(SysFile.class, JacksonConfig.LongFieldsAsNumber.ForSysFile.class);
        assertMixinMatches(KnowledgeFileVO.class, JacksonConfig.LongFieldsAsNumber.ForKnowledgeFileVO.class);
        assertMixinMatches(QuotaVO.class, JacksonConfig.LongFieldsAsNumber.ForQuotaVO.class);
        assertMixinMatches(Result.class, JacksonConfig.LongFieldsAsNumber.ForResult.class);
    }

    private static void assertMixinMatches(Class<?> target, Class<?> mixin) {
        Method[] declared = mixin.getDeclaredMethods();
        assertTrue(declared.length > 0,
                mixin.getSimpleName() + " 一个属性都没声明 —— 它在 JacksonConfig 里注册了却不起任何作用。");
        for (Method m : declared) {
            assertDoesNotThrow(() -> target.getMethod(m.getName()),
                    mixin.getSimpleName() + " 声明了 " + m.getName() + "()，但 "
                            + target.getSimpleName() + " 上没有这个方法。"
                            + " mixin 是按方法名匹配的，**匹配不上不会报错，只是静默失效** ——"
                            + " 那个字段会悄无声息地退回字符串形态（2026-10-08 那批字段就是这么被漏掉的）。"
                            + " 实体用 Lombok，getter 名要与字段一一对应，改了字段名就得同步改这里。");
        }
    }

    @SuppressWarnings("unchecked")
    private static SseEvent lastOf(Recorder r, String event) {
        return r.frames.stream()
                .filter(f -> event.equals(f.getEvent()))
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("没有 " + event + " 事件，实际有："
                        + r.frames.stream().map(SseEvent::getEvent).toList()));
    }

    private static class Recorder extends SseResponseConverter {
        final List<SseEvent> frames = new ArrayList<>();

        Recorder() {
            super(new SseEmitter(), false, mock(ChatHistoryListService.class),
                    SESSION_ID, 1L, "你好", RUN_ID, 1, 1L,
                    // 本测试不涉及工具可见性：传 null = 不隐藏任何工具
                    null);
            start();
        }

        @Override
        protected void dispatch(SseEvent event) {
            frames.add(event);
        }
    }
}
