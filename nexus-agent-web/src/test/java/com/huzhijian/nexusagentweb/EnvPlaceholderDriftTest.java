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

    @Test
    @DisplayName("专项回归：ALI_AI_KEY 仍被百炼（qwen）使用，不许再被文档写成『不需要』")
    void aliAiKeyMustStayDeclared() throws IOException {
        Path root = repoRoot();
        String yml = Files.readString(root.resolve("nexus-agent-web/src/main/resources/application-prod.yml"));
        assertTrue(yml.contains("${ALI_AI_KEY}"),
                "prod yml 里已经不用 ALI_AI_KEY 了？那这个专项断言可以删掉");

        List<String> hits = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve(".env.example"))) {
            if (line.contains("ALI_AI_KEY")) {
                hits.add(line.trim());
            }
        }
        assertFalse(hits.isEmpty(), ".env.example 里完全找不到 ALI_AI_KEY");
        assertTrue(hits.stream().anyMatch(l -> l.startsWith("ALI_AI_KEY=")),
                "ALI_AI_KEY 必须有一条**未被注释**的 `ALI_AI_KEY=` 行（它仍在给百炼 qwen 供货），"
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
