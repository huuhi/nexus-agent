package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser.SkillDraft;
import com.huzhijian.nexusagentweb.skills.SkillPackageParser.SkillResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillPackageParser} 的纯单元测试。
 * <p>
 * 重点覆盖<b>恶意输入</b>：路径穿越、压缩炸弹、超大文件、缺 frontmatter、
 * 非法技能名。这些是上传通道的信任边界，每一条都要有断言兜着。
 */
class SkillPackageParserTest {

    private static final String VALID_SKILL_MD = """
            ---
            name: my-skill
            description: 处理 PDF 摘要的技能，仅在用户提到摘要 PDF 时使用。
            ---

            # 步骤
            1. 读取文件
            2. 输出摘要
            """;

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("单个 .md 文件能解析出 name / description / 正文")
    void parsesSingleMarkdown() {
        SkillDraft draft = SkillPackageParser.parse(
                VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8), "any.md");

        assertEquals("my-skill", draft.getName());
        assertEquals("处理 PDF 摘要的技能，仅在用户提到摘要 PDF 时使用。", draft.getDescription());
        assertTrue(draft.getContent().contains("# 步骤"));
        // 正文里不该再残留 frontmatter
        assertFalse(draft.getContent().contains("description:"));
    }

    @Test
    @DisplayName("zip 里 SKILL.md 在根目录，其余文本文件收为资源")
    void parsesFlatZip() throws IOException {
        byte[] zip = zip(
                entry("SKILL.md", VALID_SKILL_MD),
                entry("notes.md", "这里是补充资料"),
                entry("data/config.yaml", "k: v"));

        SkillDraft draft = SkillPackageParser.parse(zip, "pack.zip");

        assertEquals("my-skill", draft.getName());
        assertEquals(2, draft.getResources().size());
        assertTrue(draft.getResources().stream()
                .anyMatch(r -> r.path().equals("notes.md") && r.content().contains("补充资料")));
    }

    @Test
    @DisplayName("zip 整体套一层 <skill>/ 目录也能识别，且资源路径剥掉前缀")
    void parsesNestedZip() throws IOException {
        byte[] zip = zip(
                entry("my-skill/SKILL.md", VALID_SKILL_MD),
                entry("my-skill/notes.md", "资料"));

        SkillDraft draft = SkillPackageParser.parse(zip, "pack.zip");

        assertEquals("my-skill", draft.getName());
        assertEquals(1, draft.getResources().size());
        // 关键：路径必须是相对技能根目录的，不能带 my-skill/ 前缀
        assertEquals("notes.md", draft.getResources().get(0).path());
    }

    @Test
    @DisplayName("scripts/ 下的文件被刻意忽略（库读不到，也不该托管可执行内容）")
    void ignoresScriptsDir() throws IOException {
        byte[] zip = zip(
                entry("SKILL.md", VALID_SKILL_MD),
                entry("scripts/run.py", "import os; os.system('rm -rf /')"),
                entry("notes.md", "资料"));

        SkillDraft draft = SkillPackageParser.parse(zip, "pack.zip");

        assertEquals(1, draft.getResources().size());
        assertEquals("notes.md", draft.getResources().get(0).path());
    }

    @Test
    @DisplayName("非白名单扩展名（二进制/图片）被忽略而不是报错")
    void ignoresBinaryFiles() throws IOException {
        byte[] zip = zip(
                entry("SKILL.md", VALID_SKILL_MD),
                entry("logo.png", "PNG fake"),
                entry("app.jar", "MZ fake"));

        SkillDraft draft = SkillPackageParser.parse(zip, "pack.zip");

        assertTrue(draft.getResources().isEmpty());
    }

    @Test
    @DisplayName("Windows 换行（CRLF）的 SKILL.md 也能解析")
    void handlesCrlf() {
        String crlf = VALID_SKILL_MD.replace("\n", "\r\n");
        SkillDraft draft = SkillPackageParser.parse(
                crlf.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals("my-skill", draft.getName());
        assertTrue(draft.getContent().contains("# 步骤"));
    }

    @Test
    @DisplayName("带 BOM 的文件不会误判为「无 frontmatter」")
    void handlesBom() {
        String withBom = "﻿" + VALID_SKILL_MD;
        SkillDraft draft = SkillPackageParser.parse(
                withBom.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals("my-skill", draft.getName());
    }

    @Test
    @DisplayName("引号内的 # 不被当成行尾注释（YAML 语义：引号里 # 是内容）")
    void keepsHashInsideQuotedDescription() {
        //  不加引号时 ` # 重点` 在 YAML 里本来就是注释，会被正确截断 ——
        //  所以要保住这个 #，真实写法就得加引号。
        String md = """
                ---
                name: csharp-skill
                description: "处理 C# 代码相关任务 # 重点"
                ---

                正文
                """;
        SkillDraft draft = SkillPackageParser.parse(md.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals("处理 C# 代码相关任务 # 重点", draft.getDescription());
    }

    @Test
    @DisplayName("引号内无空格紧邻的 # 不被当注释（C# 这种语言名）")
    void keepsHashWithoutPrecedingSpace() {
        String md = """
                ---
                name: dotnet-skill
                description: 处理 C# 与 F# 项目
                ---

                正文
                """;
        SkillDraft draft = SkillPackageParser.parse(md.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals("处理 C# 与 F# 项目", draft.getDescription());
    }

    @Test
    @DisplayName("技能名会统一转小写")
    void lowercasesName() {
        String md = """
                ---
                name: MySkill
                description: 测试
                ---

                正文
                """;
        SkillDraft draft = SkillPackageParser.parse(md.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals("myskill", draft.getName());
    }

    // ==================== 安全边界 ====================

    @Test
    @DisplayName("路径穿越条目（../）整包拒绝")
    void rejectsPathTraversal() throws IOException {
        byte[] zip = zip(
                entry("SKILL.md", VALID_SKILL_MD),
                entry("../../etc/passwd", "root:x:0:0"));

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(zip, "evil.zip"));
        assertTrue(e.getMessage().contains("非法路径"));
    }

    @Test
    @DisplayName("绝对路径条目被拒绝")
    void rejectsAbsolutePath() throws IOException {
        byte[] zip = zip(entry("/etc/shadow", "data"));

        assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(zip, "evil.zip"));
    }

    @Test
    @DisplayName("单条目超过 1MB 被拒绝（防解压炸弹）")
    void rejectsOversizedEntry() throws IOException {
        byte[] huge = new byte[1024 * 1024 + 1024];
        java.util.Arrays.fill(huge, (byte) 'a');
        byte[] zip = zip(
                entry("SKILL.md", VALID_SKILL_MD),
                entry("big.txt", new String(huge, StandardCharsets.UTF_8)));

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(zip, "bomb.zip"));
        assertTrue(e.getMessage().contains("过大"));
    }

    @Test
    @DisplayName("条目数超上限被拒绝（防「百万空文件」型炸弹）")
    void rejectsTooManyEntries() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (int i = 0; i < 250; i++) {
                zos.putNextEntry(new ZipEntry("f" + i + ".md"));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(bos.toByteArray(), "bomb.zip"));
        assertTrue(e.getMessage().contains("过多"));
    }

    @Test
    @DisplayName("缺 frontmatter 被拒绝")
    void rejectsMissingFrontmatter() {
        byte[] md = "# 没有 frontmatter\n\n正文".getBytes(StandardCharsets.UTF_8);

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(md, "x.md"));
        assertTrue(e.getMessage().contains("frontmatter"));
    }

    @Test
    @DisplayName("frontmatter 未闭合被拒绝")
    void rejectsUnclosedFrontmatter() {
        byte[] md = "---\nname: x\ndescription: y\n\n正文".getBytes(StandardCharsets.UTF_8);

        assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(md, "x.md"));
    }

    @Test
    @DisplayName("zip 内没有 SKILL.md 被拒绝")
    void rejectsZipWithoutSkillMd() throws IOException {
        byte[] zip = zip(entry("readme.md", "随便一个文件"));

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(zip, "pack.zip"));
        assertTrue(e.getMessage().contains("SKILL.md"));
    }

    @Test
    @DisplayName("缺 description 被拒绝（模型靠它做激活决策）")
    void rejectsMissingDescription() {
        byte[] md = "---\nname: x\n---\n\n正文".getBytes(StandardCharsets.UTF_8);

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(md, "x.md"));
        assertTrue(e.getMessage().contains("description"));
    }

    @Test
    @DisplayName("非法技能名（含空格 / 中文 / 下划线）被拒绝")
    void rejectsIllegalName() {
        for (String bad : List.of("my skill", "中文名", "my_skill", "-leading")) {
            String md = "---\nname: \"" + bad + "\"\ndescription: 测试\n---\n\n正文";
            assertThrows(ValidationException.class,
                    () -> SkillPackageParser.parse(md.getBytes(StandardCharsets.UTF_8), "x.md"),
                    "技能名应被拒绝：" + bad);
        }
    }

    @Test
    @DisplayName("空正文被拒绝")
    void rejectsEmptyContent() {
        byte[] md = "---\nname: x\ndescription: 测试\n---\n\n".getBytes(StandardCharsets.UTF_8);

        assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(md, "x.md"));
    }

    @Test
    @DisplayName("空文件被拒绝")
    void rejectsEmptyFile() {
        assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(new byte[0], "x.md"));
    }

    @Test
    @DisplayName("不支持的扩展名被拒绝")
    void rejectsUnsupportedExtension() {
        byte[] md = VALID_SKILL_MD.getBytes(StandardCharsets.UTF_8);

        ValidationException e = assertThrows(ValidationException.class,
                () -> SkillPackageParser.parse(md, "skill.exe"));
        assertTrue(e.getMessage().contains(".zip"));
    }

    @Test
    @DisplayName("不是合法 zip 的内容报「解析失败」而不是静默通过")
    void rejectsCorruptZip() {
        byte[] garbage = "这不是 zip 文件".getBytes(StandardCharsets.UTF_8);

        assertThrows(ParserFileException.class,
                () -> SkillPackageParser.parse(garbage, "pack.zip"));
    }

    @Test
    @DisplayName("超长 description 被截断而不是直接拒绝（列表页展示用）")
    void truncatesLongDescription() {
        String longDesc = "描".repeat(900);
        String md = "---\nname: x\ndescription: \"" + longDesc + "\"\n---\n\n正文";
        SkillDraft draft = SkillPackageParser.parse(md.getBytes(StandardCharsets.UTF_8), "x.md");

        assertEquals(500, draft.getDescription().length());
    }

    // ==================== 辅助 ====================

    private static byte[] zip(Map.Entry<String, String>... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static Map.Entry<String, String> entry(String name, String content) {
        return Map.entry(name, content);
    }
}