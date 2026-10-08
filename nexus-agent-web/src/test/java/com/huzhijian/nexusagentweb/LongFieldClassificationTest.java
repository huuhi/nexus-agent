package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.JacksonConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-08「Long 字段形态」两轮事故的终极防线：**强制显式分类**。
 * <p>
 * <b>为什么需要它</b>：全局 {@code JacksonConfig} 把 {@code Long} / {@code long} 序列化成字符串
 * （为雪花 ID 精度，必要）。这条规则**一刀切**，于是每新增一个 {@code Long} 字段，
 * 作者都必须在心里做一个判断：「它是主键（该保持字符串）还是计量值（该是 number）」——
 * 而这个判断<b>做错了不会有任何提示</b>：编译过、测试绿、运行时静默下发成另一种形态。
 * <p>
 * 当天两轮踩的都是这个：
 * <ul>
 *   <li>第一批：{@code seq} / {@code ttfbMs} 被误伤成 {@code "7"} / {@code "6667"}，
 *       前端的跳号检测因 {@code typeof seq === 'number'} 不成立而**整个绕过**；</li>
 *   <li>第二批：{@code fileSize} / {@code total} / 六个配额字段，前端要做算术却拿到字符串；</li>
 *   <li>补漏：我第一遍改了 {@code SysFile.fileSize} 和 {@code KnowledgeFileVO.fileSize}，
 *       <b>漏了 {@code AttachedFileVO.fileSize}</b> —— 同一个语义散在多个类里，靠人记必漏。</li>
 * </ul>
 * <p>
 * <b>本护栏的形态</b>：不猜语义（按名字判定会漏掉 {@code supersededBy} 这类不含 {@code Id} 的外键 ——
 * 它就在清单里），而是要求每个 {@code Long} 字段**出现在下面的显式清单里**，
 * 清单分两类：{@link #MEASURED}（计量值，必须进 mixin）与 {@link #IDS}（主键类，必须保持字符串）。
 * <p>
 * 🔴 <b>新增一个 {@code Long} 字段时，这个测试会红，直到你做一次显式决定。</b>
 * 这才是重点 —— 它把「静默的判断失误」变成「编译期就被拦住的问题」。
 * <p>
 * ⚠️ 清单与运行时配置**双向核对**：{@link #MEASURED} 必须与
 * {@code JacksonConfig.mixins()} 里实际声明的属性完全一致（多一个少一个都红），
 * 免得出现「清单写了一堆、mixin 没配」或反过来的空转状态。
 */
@DisplayName("Long 字段强制分类（新增 Long 字段必须在清单里显式决定形态）")
class LongFieldClassificationTest {

    /**
     * **必须是 JSON number** 的计量字段（体积 / 条数 / 配额）。
     * <p>
     * 这些是「前端要拿去做算术」的值：格式化体积、分页计算、「已用 X / 上限 Y」。
     * 每一项都必须同时出现在 {@link JacksonConfig.LongFieldsAsNumber} 的某个 mixin 里。
     */
    private static final Set<String> MEASURED = Set.of(
            "SysFile.fileSize",
            "KnowledgeFileVO.fileSize",
            "AttachedFileVO.fileSize",
            "QuotaVO.quota",
            "QuotaVO.used",
            "QuotaVO.remaining",
            "QuotaVO.fileQuota",
            "QuotaVO.fileUsed",
            "QuotaVO.fileRemaining",
            "Result.total",
            "User.tokenQuota",
            "User.tokenUsed",
            "User.fileQuota");

    /**
     * **必须保持 JSON 字符串**的主键类字段。
     * <p>
     * 它们要么是 19 位雪花主键，要么是引用主键的外键（{@code supersededBy} 就属后者 ——
     * 名字里没有 {@code Id}，所以「按名字猜」的判定方式一定会漏掉它）。
     * 裸数字过一遍 {@code JSON.parse} 会丢低位 → 该接口的 id 操作**静默失效**
     * （2026-10-05「文件删不掉」就是这类）。
     */
    private static final Set<String> IDS = Set.of(
            "ChatHistory.id",
            "ChatHistory.userId",
            "ChatHistory.supersededBy",
            "ChatHistoryList.userId",
            "LexiangCredential.id",
            "LexiangCredential.userId",
            "McpInformation.id",
            "McpInformation.userId",
            "SysFile.id",
            "SysFile.userId",
            "SystemLog.id",
            "User.id",
            "UserConfig.userId",
            "UserMemory.id",
            "UserMemory.userId",
            "UserSkill.id",
            "UserSkill.userId",
            "KnowledgeFileVO.id",
            "McpDetailVO.id",
            "MessageVO.id",
            "MessageVO.supersededBy",
            "SkillDetailVO.id",
            "SkillVO.id",
            "UserMemoryVO.id",
            "UserProfileVO.id");

    /** 要扫描的包：实体的 id 与 VO 的对外字段都在这里 */
    private static final List<String> PACKAGES = List.of(
            "com.huzhijian.nexusagentweb.domain",
            "com.huzhijian.nexusagentweb.vo");

    @Test
    @DisplayName("每个 Long 字段都必须被显式分类：要么是计量值，要么是主键——不许有「没想过」的")
    void everyLongFieldIsClassified() throws Exception {
        Set<String> scanned = scanLongFields();
        assertTrue(scanned.size() >= 20,
                "只扫到 " + scanned.size() + " 个 Long 字段，扫描逻辑多半失效了 ——"
                        + "护栏静默通过比没有护栏更危险。");

        List<String> unclassified = new ArrayList<>();
        for (String field : scanned) {
            if (!MEASURED.contains(field) && !IDS.contains(field)) {
                unclassified.add(field);
            }
        }

        if (!unclassified.isEmpty()) {
            fail("以下 Long 字段没有被显式分类：\n  - " + String.join("\n  - ", unclassified)
                    + "\n\n全局 JacksonConfig 把所有 Long 序列化成字符串（为雪花 ID 精度）。"
                    + "请为每个新字段做一个显式决定：\n"
                    + "  · 它是主键 / 引用主键吗 → 加进本测试的 IDS（保持字符串，什么都不用改）；\n"
                    + "  · 它是前端要算算术的计量值吗（体积 / 条数 / 配额）→ 加进 MEASURED，"
                    + "并在 JacksonConfig.LongFieldsAsNumber 里补一个 mixin 声明。\n"
                    + "**做错的后果是静默的**：编译过、原来的测试也绿，只是前端拿到另一种形态 ——"
                    + " 2026-10-08 因此连踩两轮（seq/ttfbMs 让跳号检测整个绕过；"
                    + "fileSize/total/配额让前端算不出数）。这条护栏就是为了让那次判断无法被跳过。");
        }
    }

    @Test
    @DisplayName("MEASURED 清单与实际注册的 mixin 必须完全一致（双向，防「清单空转」）")
    void measuredListMatchesRuntimeMixins() {
        Set<String> declared = declaredMixinProperties();

        Set<String> missingInConfig = new LinkedHashSet<>(MEASURED);
        missingInConfig.removeAll(declared);
        assertTrue(missingInConfig.isEmpty(),
                "MEASURED 里声明了、但 JacksonConfig 的 mixin 里没配：" + missingInConfig
                        + " —— 清单写了却没生效，它们仍会以字符串下发。");

        Set<String> extraInConfig = new LinkedHashSet<>(declared);
        extraInConfig.removeAll(MEASURED);
        assertTrue(extraInConfig.isEmpty(),
                "JacksonConfig 的 mixin 里配了、但 MEASURED 清单里没有：" + extraInConfig
                        + " —— 要么是漏登记（补进清单），要么是已废弃没删（从 mixin 里删掉）。"
                        + " 两边必须严格一一对应，否则这份清单就不再是「当前所有决定」的记录。");
    }

    @Test
    @DisplayName("主键类字段绝不允许出现在 mixin 里（那会让 19 位精度被 JS 抹掉）")
    void idFieldsAreNotInMixins() {
        Set<String> wrongSide = declaredMixinProperties();
        wrongSide.retainAll(IDS);
        assertTrue(wrongSide.isEmpty(),
                "这些是主键类字段，却被 mixin 声明成 JSON number：" + wrongSide
                        + "\nJS 的 Number 只能精确表示 16 位整数，19 位雪花 ID 过一遍 JSON.parse 会丢低位，"
                        + "导致「拿 id 去删 / 改」的接口全部落空 —— 2026-10-05「每一行都删不掉」就是它。"
                        + " 主键必须保持字符串。");
    }

    @Test
    @DisplayName("分类清单里不能有已不存在的字段（防清单腐化）")
    void classificationHasNoStaleEntries() throws Exception {
        Set<String> scanned = scanLongFields();

        Set<String> stale = new LinkedHashSet<>(MEASURED);
        stale.addAll(IDS);
        stale.removeAll(scanned);

        assertTrue(stale.isEmpty(),
                "以下字段在分类清单里，但代码里已经找不到（改了名 / 删了类？）：" + stale
                        + " —— 清单腐化后就不再是「当前所有 Long 字段的决定记录」，"
                        + "下次有人照着它判断会得到错误结论。请同步删除或改名。");
    }

    /** 扫描 domain + vo 包下所有 {@code Long} / {@code long} 字段，返回 {@code 简单类名.字段名} */
    private static Set<String> scanLongFields() throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (String pkg : PACKAGES) {
            for (Class<?> type : loadClasses(pkg)) {
                for (Field field : type.getDeclaredFields()) {
                    // static 字段不参与 Jackson 序列化（是常量，不是实例状态），不构成形态风险
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getType() == Long.class || field.getType() == long.class) {
                        result.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        return result;
    }

    /**
     * 从运行时配置里反推出「被声明为 number 的属性」集合。
     * <p>
     * ⚠️ 走 {@code JacksonConfig.mixins()} 而不是在本测试里另抄一份类名映射 ——
     * 否则这份映射也会成为「两处各写一遍」的新漂移点。
     */
    private static Set<String> declaredMixinProperties() {
        Map<String, String> found = new LinkedHashMap<>();
        JacksonConfig.mixins().forEach((target, mixin) -> {
            for (Method m : mixin.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.startsWith("get") || name.length() <= 3) {
                    continue;
                }
                // getFileSize → fileSize
                String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                found.put(target.getSimpleName() + "." + property, mixin.getSimpleName());
            }
        });
        return new LinkedHashSet<>(found.keySet());
    }

    /** 列出包下的顶层类（跳过内部类） */
    private static List<Class<?>> loadClasses(String pkg) throws Exception {
        URL url = Thread.currentThread().getContextClassLoader().getResource(pkg.replace('.', '/'));
        assertNotNull(url, "定位不到包 " + pkg);
        assertTrue("file".equals(url.getProtocol()),
                "包 " + pkg + " 不是目录（" + url.getProtocol() + "），本护栏无法扫描");

        File dir = new File(url.toURI());
        File[] classFiles = dir.listFiles((d, name) -> name.endsWith(".class") && !name.contains("$"));
        assertNotNull(classFiles, "列不出 " + dir + " 下的 class 文件");

        List<Class<?>> classes = new ArrayList<>();
        for (File f : classFiles) {
            String simpleName = f.getName().substring(0, f.getName().length() - ".class".length());
            classes.add(Class.forName(pkg + "." + simpleName));
        }
        return classes;
    }
}
