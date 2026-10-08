package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-08「字段已经发了，前端却一个都没接」事故的防复发护栏。
 * <p>
 * <b>事故经过</b>：2026-10-06 后端往 {@code finish} / {@code stopped} / {@code error} 三个收尾事件的
 * data 里加了 {@code ttfbMs} 与 {@code contextWindow}/{@code contextUsed}/{@code contextMsgs}/
 * {@code contextRatio}，但只写进了 {@code docs/前端增量变更.md}（一个按时间追加的流水账），
 * <b>没有写进权威契约 {@code docs/sse-contract.md}</b>。
 * <p>
 * 于是 frontend 侧「按文件接契约」的做法完全接不到 —— 他查契约时搜不到
 * {@code contextRatio}，只好继续用早先硬编码的「消息条数 >= 12」判断长会话，
 * 于是在 {@code contextWindow=300000} 而实际只用了 1.2% 的会话上弹出
 * 「建议新建对话」，被用户当成 bug 报回来。
 * <p>
 * <b>这条事故的教训不是「忘了写文档」，而是「两个交付口径并存时，只有一个算数，且没人检查」。</b>
 * 所以护栏不问「你写没写」，而是直接<b>从实现里抽出字段名，再去文档里找</b> ——
 * 实现新增一个 {@code context*} / {@code ttfb*} 字段而契约没跟上，测试立刻变红。
 * <p>
 * <b>抽取口径</b>：源码里所有 {@code data.put("<context|ttfb 开头>", …)} 的字段名。
 * 之所以限定这两个前缀而不是抓全部 {@code data.put}：{@code finish} 的 {@code status}、
 * {@code stopped} 的 {@code reason}/{@code partial}、{@code error} 的 {@code type}/{@code message}/{@code hint}
 * 各有归属小节，真正的漂移风险集中在「后来批量追加、又只发增量文档」的那一批 —— 正是本次事故那一批。
 * <p>
 * ⚠️ 本护栏只查「实现了但文档没写」。反向（文档写了但实现没发）由 {@code SseContractTest} 一类
 * 面向实现的用例负责，两者互补。
 */
@DisplayName("SSE 契约文档漂移（收尾事件的附加字段必须在权威契约里出现）")
class SseContractDocDriftTest {

    /** 下发收尾附加字段的地方 */
    private static final String CONVERTER = "nexus-agent-service/src/main/java/com/huzhijian/"
            + "nexusagentweb/converter/SseResponseConverter.java";

    /** 权威契约（唯一算数的那份） */
    private static final String CONTRACT = "docs/sse-contract.md";

    /** 流水账式的增量说明 —— 它<b>不算</b>契约，写在里面不代表前端能接到 */
    private static final String CHANGELOG_DOC = "docs/前端增量变更.md";

    /** 需要覆盖的字段前缀 */
    private static final Pattern PUT_FIELD = Pattern.compile("data\\.put\\(\"((?:context|ttfb)[A-Za-z0-9]*)\"");

    /** surefire 的工作目录是模块目录，向上找到仓库根（有 .env.example 的那一层） */
    private static Path repoRoot() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            if (Files.exists(dir.resolve(".env.example"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IOException("找不到仓库根目录（向上 5 层都没有 .env.example）");
    }

    @Test
    @DisplayName("SseResponseConverter 下发的每个 context*/ttfb* 字段，都必须在 docs/sse-contract.md 里有说明")
    void everyContextFieldIsDocumentedInTheAuthoritativeContract() throws IOException {
        Path root = repoRoot();
        String source = Files.readString(root.resolve(CONVERTER));
        String contract = Files.readString(root.resolve(CONTRACT));

        Set<String> fields = new LinkedHashSet<>();
        Matcher m = PUT_FIELD.matcher(source);
        while (m.find()) {
            fields.add(m.group(1));
        }

        assertFalse(fields.isEmpty(),
                "在 " + CONVERTER + " 里没抽到任何 context*/ttfb* 字段。"
                        + "要么字段被改了名（那本护栏必须同步改），要么抽取正则失效了 ——"
                        + "护栏静默通过比没有护栏更危险。");

        List<String> missing = new ArrayList<>();
        for (String field : fields) {
            if (!contract.contains(field)) {
                missing.add(field);
            }
        }

        if (!missing.isEmpty()) {
            fail("以下字段已在 " + CONVERTER + " 里下发给前端，但权威契约 " + CONTRACT + " 里搜不到：\n  - "
                    + String.join("\n  - ", missing)
                    + "\n\n前端是「按契约文件接」的，契约里没有就等于没有这个字段。"
                    + "2026-10-06 的 ttfbMs/context* 五个字段就是只写进了 " + CHANGELOG_DOC
                    + "（流水账，前端不会去那里找），结果前端一个都没接 ——"
                    + "长会话引导至今还在数消息条数，在 window=300000、实际用量 1.2% 的会话上误报"
                    + "「建议新建对话」。请把这几个字段补进 " + CONTRACT
                    + " 的对应小节（收尾公共字段以 §3.6 为准，stopped/error 引用它），"
                    + "再把文件同步给 frontend。");
        }
    }

    /**
     * 反向确认：{@code status} 这类<b>各事件专属</b>的字段不该被本护栏的抽取范围扫进来。
     * <p>
     * 如果哪天有人把前缀放开成「全部 data.put」，这条会红 —— 它守的是护栏本身的边界，
     * 避免护栏因为范围过宽而在无关改动上乱报，最终被人嫌烦删掉。
     */
    @Test
    @DisplayName("抽取范围保持在 context*/ttfb*，不误伤各事件专属字段")
    void extractionScopeStaysNarrow() throws IOException {
        String source = Files.readString(repoRoot().resolve(CONVERTER));
        Set<String> fields = new LinkedHashSet<>();
        Matcher m = PUT_FIELD.matcher(source);
        while (m.find()) {
            fields.add(m.group(1));
        }

        for (String field : fields) {
            assertTrue(field.startsWith("context") || field.startsWith("ttfb"),
                    "抽到了不属于收尾附加字段的 " + field + " —— 抽取正则被放得太宽了。"
                            + "请把范围收回到 context*/ttfb*：专属字段各有归属小节，"
                            + "混进来会让本护栏在无关改动上乱报。");
        }
    }
}
