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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠️ 这个测试**不测代码，测的是"文档与配置是否一致"** —— 因为这类不一致
 * 在 2026-10-04 真的把线上搞挂过一次：
 * <p>
 * `.env.example` 里写着「pgvector 下线后 {@code ALI_AI_KEY} 不再需要」，
 * 但 `application-prod.yml` 的 `nexus.agent.system-models[qwen].apiKey` 用的还是
 * {@code ${ALI_AI_KEY}}。结果用户照模板不填 → 启动失败；填错（拿 OSS 的 AccessKey 顶上）
 * → 对话时报 {@code Incorrect API key provided}。
 * <p>
 * 纯单测永远抓不到这种问题：它不读 yml、也不读 .env.example。
 * 所以这里直接把两份文件当**输入**来断言，让"文档漂移"变成一条失败的测试。
 */
@DisplayName("环境变量清单 ↔ prod 配置占位符（防文档漂移）")
class EnvPlaceholderDriftTest {

    /** `${XXX}` 与 `${XXX:默认值}`：第 2 组非空表示**有默认值**（缺了不致命） */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(:([^}]*))?}");

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
    @DisplayName("prod yml 里每个**无默认值**的占位符，都必须在 .env.example 里有对应的 KEY= 行")
    void everyRequiredPlaceholderIsDeclared() throws IOException {
        Path root = repoRoot();
        String yml = Files.readString(root.resolve("nexus-agent-web/src/main/resources/application-prod.yml"));

        Set<String> required = new TreeSet<>();
        for (String rawLine : yml.lines().toList()) {
            String line = rawLine.trim();
//            注释里也有 `${XXX}`（那是写给读者看的示例），不能当成真实占位符
            if (line.startsWith("#")) {
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(line);
            while (m.find()) {
                if (m.group(2) == null) {
                    required.add(m.group(1));
                }
            }
        }
        assertFalse(required.isEmpty(), "没解析到任何必需占位符 —— 正则或文件路径可能变了");

        Set<String> declared = new TreeSet<>();
        for (String line : Files.readAllLines(root.resolve(".env.example"))) {
            String text = line.trim();
//            注释掉的（`# KEY=xxx`）不算声明：那正是当年 ALI_AI_KEY 被"写没了"的形态
            if (text.startsWith("#") || !text.contains("=")) {
                continue;
            }
            declared.add(text.substring(0, text.indexOf('=')).trim());
        }

        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(declared);

        assertTrue(missing.isEmpty(),
                "application-prod.yml 用到这些占位符，但 .env.example 里没有未被注释的 `KEY=` 行 —— "
                        + "用户照模板填就会启动失败（Could not resolve placeholder），"
                        + "或者填错值后在运行期才炸："
                        + missing);
    }

    /**
     * 专项回归：<b>只要 prod yml 里还有任何一个 {@code ${ALI_AI_KEY}}，
     * .env.example 就必须有未被注释的 {@code ALI_AI_KEY=} 行。</b>
     *
     * <p><b>2026-10-05 改造</b>：原来是硬断言「必须有未注释的 ALI_AI_KEY= 行」，
     * 那条断言在百炼从 {@code system-models} 移除后就与现实矛盾了
     * （{@code .env.example} 里那一行按新事实被注释掉，测试直接红）。
     * 真正要守的不变式是<b>「引用了占位符就必须能填」</b>，而不是「这个占位符必须存在」——
     * 否则将来再下线任何一个服务商，都得改一遍这个测试。
     *
     * <p>反向的坑也一并守住：若 prod yml 里其实<b>还在用</b> ${ALI_AI_KEY}，
     * 而 .env.example 把它注释掉了，那正是 2026-10-04 那次事故的形态
     * （用户照模板不填 → 启动直接失败）。现在这条会在 yml 含占位符时立刻报出来。
     */
    @Test
    @DisplayName("专项回归：prod yml 只要还用 ${ALI_AI_KEY}，.env.example 就必须能填")
    void aliAiKeyStaysFillableWhenReferenced() throws IOException {
        Path root = repoRoot();
        // 🔴 只看**非注释行**：yml 里常有「说明性注释」写着 ${ALI_AI_KEY}（告诉读者这个占位符是什么），
        //    用 contains() 全文匹配会把它当成真的引用 → 假阳性（本轮实测踩到）。
        //    同一个坑上面 everyRequiredPlaceholderIsDeclared 已经踩过一次并处理过。
        boolean referenced = Files.readAllLines(root.resolve("nexus-agent-web/src/main/resources/application-prod.yml"))
                .stream()
                .map(String::trim)
                .anyMatch(line -> !line.startsWith("#") && line.contains("${ALI_AI_KEY}"));
        List<String> hits = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve(".env.example"))) {
            if (line.contains("ALI_AI_KEY")) {
                hits.add(line.trim());
            }
        }

        if (!referenced) {
            // 百炼已从 system-models 移除（2026-10-05，供应商换成小米 MIMO）。
            // prod 不再引用它 → .env.example 里那一行注释掉是正确的，不要求可填。
            // 但仍要求「文档里还提得起它」，否则将来想换回来的人查不到。
            assertFalse(hits.isEmpty(),
                    "prod yml 已不用 ${ALI_AI_KEY}，但 .env.example 里连相关说明都删干净了 —— "
                            + "将来想换回百炼的人会查不到该去哪儿拿 Key");
            return;
        }

        // prod 还在引用 → 必须是可填的（未被注释）
        assertTrue(hits.stream().anyMatch(l -> l.startsWith("ALI_AI_KEY=")),
                "prod yml 里在用 ${ALI_AI_KEY}，但 .env.example 里没有未被注释的 `ALI_AI_KEY=` 行 —— "
                        + "用户照模板不填就会启动失败（Could not resolve placeholder），"
                        + "当前出现的行是：" + hits);
    }

    /** 顺带确认：两个文件都能读到，避免上面两条因为"文件不存在"而假通过 */
    @Test
    @DisplayName("两份文件都真实存在（防止断言被空集合绕过）")
    void bothFilesExist() throws IOException {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve(".env.example")));
        assertTrue(Files.exists(root.resolve("nexus-agent-web/src/main/resources/application-prod.yml")));
    }

    /** 让报错信息更好读：把集合按原始顺序保留 */
    @SuppressWarnings("unused")
    private static Set<String> ordered(Set<String> in) {
        return new LinkedHashSet<>(in);
    }
}
