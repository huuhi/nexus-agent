package com.huzhijian.nexusagentweb.skills;

import com.huzhijian.nexusagentweb.exception.ParserFileException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import lombok.Builder;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能包解析器：把用户上传的 {@code .zip} / {@code .skill} / {@code .md} 解析成一份
 * <b>纯内存</b>的技能草稿。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 * 说明: <b>全程不落盘</b> —— 这是刻意的设计。
 * <p>
 * 常见做法是把 zip 解压到 {@code skills/users/<userId>/}，但那样要处理四件事：
 * 路径穿越（zip slip）、zip 炸弹、删除时机、多用户目录隔离。
 * 而 {@code Skills.from(...)} 接受任意 {@code Skill} 实现，正文和资源都能放内存里装配，
 * 于是<b>落盘这一步被整个消掉</b>，前三个问题也就不存在了。
 * <p>
 * <b>技能包格式</b>（与 Claude Code / Anthropic Agent Skills 一致）：
 * <pre>
 * my-skill.zip
 * ├── SKILL.md        必需。YAML frontmatter 提供 name / description
 * ├── notes.md         可选。read_resource 能读到
 * └── scripts/x.py     可选。<b>被刻意忽略</b>（见下方 SCRIPT_DIR）
 * </pre>
 * 单文件 {@code .md} 也允许（就是 SKILL.md 本身）。
 * <p>
 * <b>安全策略</b>（每条都对应一类真实攻击）：
 * <ul>
 *   <li>路径穿越：条目名含 {@code ..} 或绝对路径 → 整包拒绝（虽然不落盘，但严防库层漏洞）</li>
 *   <li>zip 炸弹：限制条目数、单条目大小、总解压大小三重上限</li>
 *   <li>非文本文件：只收文本扩展名，二进制（.exe/.jar/图片）直接忽略</li>
 *   <li>技能名：必须匹配 {@code ^[a-z0-9][a-z0-9-]{0,63}$}，防注入与同名覆盖</li>
 * </ul>
 * 注意这里<b>不防「技能内容有害」</b> —— 技能正文只是提示词，模型读它然后行动，
 * 真正的行为边界由工具层（{@code ToolCallGuard}、沙盒）负责。
 */
public final class SkillPackageParser {

    /**
     * 单条目最大字节数。参考文件都是给人/模型读的文本，1MB 足够宽松了。
     */
    private static final int MAX_ENTRY_BYTES = 1024 * 1024;

    /**
     * 单个包解压后的总字节上限（防 zip 炸弹）。
     * <p>
     * 不用 {@code entry.getSize()} 预判 —— 那字段来自 zip 中央目录，可被伪造，
     * 必须一边解压一边计数（实际读到的字节数）。
     */
    private static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;

    /** 条目数上限（防「百万个空文件」这种另一种炸弹） */
    private static final int MAX_ENTRIES = 200;

    /** 单个资源文件上限，比 SKILL.md 宽松些但仍有限 */
    private static final int MAX_RESOURCE_CHARS = 256 * 1024;

    /** SKILL.md 正文上限 */
    private static final int MAX_CONTENT_CHARS = 128 * 1024;

    /** description 展示在列表页，超长没意义 */
    private static final int MAX_DESCRIPTION_CHARS = 500;

    /**
     * 脚本目录。
     * <p>
     * 库刻意排除它（{@code read_resource} 读不到），约定是「脚本供<strong>执行</strong>、
     * 文档供<strong>阅读</strong>」。这里直接不收 —— 存进去也只是死数据，
     * 反而会让人误以为上传脚本就能跑。
     */
    private static final String SCRIPT_DIR = "scripts/";

    /**
     * 允许作为资源收录的扩展名。**白名单**，不在表里的一律忽略。
     * <p>
     * 之所以连 {@code .py} 都不收：技能正文可以让模型通过沙盒自行创建脚本，
     * 而沙盒里跑的东西不经过这里，也不该由这里托管。
     */
    private static final List<String> TEXT_EXTENSIONS =
            List.of("md", "markdown", "txt", "json", "yaml", "yml", "csv", "html", "xml");

    /** 技能名白名单正则：小写字母开头，只含小写字母/数字/连字符 */
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private SkillPackageParser() {
    }

    /**
     * 解析入口：按文件名后缀决定走 zip 还是单文件。
     *
     * @param content    文件字节
     * @param originalName 原始文件名（用来取后缀）
     * @return 解析出的技能草稿
     */
    public static SkillDraft parse(byte[] content, String originalName) {
        if (content == null || content.length == 0) {
            throw new ValidationException("技能包为空！");
        }
        String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return parseSingleMarkdown(content);
        }
        if (lower.endsWith(".zip") || lower.endsWith(".skill")) {
            return parseZip(content);
        }
        throw new ValidationException("只支持 .zip / .skill / .md 格式的技能包！");
    }

    /**
     * 单个 {@code .md} 文件：整份就是 SKILL.md。
     */
    static SkillDraft parseSingleMarkdown(byte[] content) {
        String text = decodeText(content);
        Parsed parsed = splitFrontmatter(text);
        // 单个 .md 没有目录名可兜底，只能靠 frontmatter 的 name（下面的 build 会校验）
        return build(parsed.frontmatter(), parsed.content(), Map.of(), null);
    }

    /**
     * zip 包：找 SKILL.md，其余文本文件作为资源。
     */
    static SkillDraft parseZip(byte[] content) {
        Map<String, String> entries = new LinkedHashMap<>();
        int totalBytes = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new ValidationException(
                            "技能包内文件过多（超过 " + MAX_ENTRIES + " 个），疑似异常包！");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                //  ==== 路径穿越防护 ====
                // 虽然我们不落盘，但 zip slip 的经典形态是恶意文件名穿透到别的目录。
                // 库层或将来任何「按 entry 名拼路径」的实现都可能中招，所以在这里就掐掉。
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")
                        || name.contains("\\") || name.contains(":")) {
                    throw new ValidationException("技能包内含非法路径：" + name);
                }
                //  scripts/ 刻意忽略（库读不到，且不该托管可执行内容）
                if (name.startsWith(SCRIPT_DIR) || name.contains("/" + SCRIPT_DIR)) {
                    zis.closeEntry();
                    continue;
                }
                //  ==== zip 炸弹防护 ====
                // 用实际读到的字节数，不信 zip 头里声明的 size（可被伪造）
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int entryBytes = 0;
                while ((n = zis.read(buf)) > 0) {
                    entryBytes += n;
                    totalBytes += n;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new ValidationException(
                                "技能包内单个文件过大（超过 1MB）：" + name);
                    }
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new ValidationException("技能包解压后超过 4MB，已拒绝（疑似压缩炸弹）！");
                    }
                    bos.write(buf, 0, n);
                }
                if (entryBytes == 0) {
                    zis.closeEntry();
                    continue;
                }
                String cleanName = normalizeEntryName(name);
                if (isCollectable(cleanName)) {
                    entries.put(cleanName, decodeText(bos.toByteArray()));
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new ParserFileException("技能包解析失败：" + e.getMessage());
        }

        //  ⚠️ ZipInputStream 遇到非法数据**不抛异常**，只是 getNextEntry() 一直返回 null。
        //  所以「一条都没读到」意味着这压根不是个 zip（比如后缀写错了、传了个 md 进来）——
        //  必须在这里区分，否则会报成「包内未找到 SKILL.md」，把用户的真实问题指歪。
        if (entryCount == 0) {
            throw new ParserFileException("文件不是有效的 zip 包（可能后缀写错了，或该传 .md 单文件）！");
        }

        //  ==== 定位 SKILL.md ====
        // 兼容两种打包习惯：根目录直接放 SKILL.md，或整体套一层 <skill-name>/ 目录
        String skillMdKey = null;
        String zipDirName = null;
        for (String key : entries.keySet()) {
            if (key.equalsIgnoreCase("SKILL.md")) {
                skillMdKey = key;
                break;
            }
        }
        if (skillMdKey == null) {
            String prefix = null;
            for (String key : entries.keySet()) {
                int slash = key.indexOf('/');
                if (slash > 0 && key.substring(slash + 1).equalsIgnoreCase("SKILL.md")) {
                    prefix = key.substring(0, slash + 1);
                    zipDirName = prefix.substring(0, slash);
                    skillMdKey = key;
                    break;
                }
            }
            if (prefix != null) {
                // 剥掉那层前缀，资源路径保持相对技能根目录
                Map<String, String> stripped = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    stripped.put(e.getKey().substring(prefix.length()), e.getValue());
                }
                entries = stripped;
                skillMdKey = "SKILL.md";
            }
        }
        if (skillMdKey == null) {
            throw new ValidationException("技能包内未找到 SKILL.md（技能必需该文件）！");
        }

        Parsed parsed = splitFrontmatter(entries.remove(skillMdKey));
        return build(parsed.frontmatter(), parsed.content(), entries, zipDirName);
    }

    /**
     * 组装草稿并做各项校验。
     *
     * @param frontmatter 解析出的 frontmatter 键值对
     * @param content    SKILL.md 正文
     * @param resources  资源键值对
     * @param zipDirName zip 内那层顶层目录名（frontmatter 未声明 name 时的兜底）
     */
    private static SkillDraft build(Map<String, String> frontmatter,
                                    String content, Map<String, String> resources,
                                    String zipDirName) {
        //  ==== 技能名：frontmatter 优先，其次目录名 ====
        String name = firstNonBlank(frontmatter.get("name"), zipDirName);
        if (name == null) {
            throw new ValidationException("技能缺少 name（SKILL.md 的 frontmatter 里必须有 name）！");
        }
        name = name.trim().toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new ValidationException(
                    "技能名不合法：" + name + "（只允许小写字母、数字、连字符，且以字母或数字开头）");
        }

        String description = frontmatter.get("description");
        if (description == null || description.isBlank()) {
            throw new ValidationException("技能缺少 description（模型靠它决定何时激活）！");
        }
        description = description.trim();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS);
        }

        if (content == null || content.isBlank()) {
            throw new ValidationException("技能正文为空！");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ValidationException("技能正文过长（超过 128K）！");
        }

        List<SkillResource> resourceList = new ArrayList<>();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            if (v.length() > MAX_RESOURCE_CHARS) {
                throw new ValidationException("资源文件过大（超过 256K）：" + e.getKey());
            }
            resourceList.add(new SkillResource(e.getKey(), v));
        }

        return SkillDraft.builder()
                .name(name)
                .description(description)
                .content(content)
                .frontmatter(frontmatter)
                .resources(resourceList)
                .build();
    }

    /**
     * 切分 YAML frontmatter。
     * <p>
     * <b>刻意不引 YAML 解析器</b>：frontmatter 只用到 name/description 两个标量字段，
     * 为此引入 SnakeYAML 依赖不划算（而且引入 YAML 解析到用户上传内容上，
     * 本身就是个攻击面）。这里只做「顶层 key: value」的行解析，够用且无依赖。
     * <p>
     * 未来真要支持嵌套结构（allowed-tools 列表等）时再换解析器，那时
     * {@link SkillDraft#frontmatter()} 会自然接住。
     */
    static Parsed splitFrontmatter(String text) {
        if (text == null) {
            throw new ValidationException("技能文件为空！");
        }
        // 去掉可能存在的 UTF-8 BOM，否则第一行不是 ---，会误判成「无 frontmatter」
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---")) {
            throw new ValidationException(
                    "SKILL.md 必须以 YAML frontmatter 开头（第一行是 ---，含 name 与 description）！");
        }
        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0) {
            throw new ValidationException("SKILL.md 格式错误：只有开头一行 ---");
        }
        String closing = "\n---";
        int closingIndex = normalized.indexOf(closing, firstLineEnd);
        if (closingIndex < 0) {
            throw new ValidationException("SKILL.md 的 frontmatter 未闭合（缺少结束的 ---）！");
        }
        String frontmatterBlock = normalized.substring(firstLineEnd + 1, closingIndex);

        Map<String, String> frontmatter = new LinkedHashMap<>();
        for (String line : frontmatterBlock.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                // 列表项（- xxx）与多行 YAML 一律忽略：本解析器只认顶层标量
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            //  ⚠️ 顺序要紧：**先剥引号，再去行尾注释**。
            //  反过来的话，`description: "做 C# 相关 # 重点"` 里的 # 会在引号还在的时候
            //  被当成注释，把后半句连引号一起砍掉。
            boolean quoted = value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")));
            if (quoted) {
                value = value.substring(1, value.length() - 1).trim();
            } else {
                value = stripTrailingComment(value);
            }
            if (!key.isEmpty() && !value.isEmpty()) {
                frontmatter.putIfAbsent(key, value);
            }
        }

        int afterClosing = normalized.indexOf('\n', closingIndex + 1);
        String content = afterClosing < 0 ? "" : normalized.substring(afterClosing + 1).trim();
        return new Parsed(frontmatter, content);
    }

    /**
     * 行尾注释只在引号外才生效，避免把 description 里的 "a # b" 截断。
     */
    private static String stripTrailingComment(String value) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble && i > 0
                    && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).trim();
            }
        }
        return value;
    }

    /** 解码为 UTF-8；失败时给出明确错误而不是静默产生乱码 */
    private static String decodeText(byte[] bytes) {
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ValidationException("技能包内含非 UTF-8 文本，请另存为 UTF-8 后重试！");
        }
    }

    /** 归一化条目名：去掉开头的 ./ 与结尾的 / */
    private static String normalizeEntryName(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /**
     * 判断条目是否值得<b>收集进 entries 暂存区</b>。
     * <p>
     * ⚠️ 注意：这里**必须**收 SKILL.md 本身。之前把它排除掉，结果后面
     * 从 entries 里找 SKILL.md 时永远找不到，所有 zip 包都报「未找到 SKILL.md」。
     * SKILL.md 不作为资源暴露给 {@code read_resource}，那是在定位它之后从
     * entries 里 remove 掉实现的。
     * <p>
     * 非白名单扩展名直接忽略（不报错 —— 别人打的包里塞张图片很常见，
     * 没必要为此拒绝整包）。
     */
    private static boolean isCollectable(String entryName) {
        int dot = entryName.lastIndexOf('.');
        if (dot < 0 || dot == entryName.length() - 1) {
            return false;
        }
        String ext = entryName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /**
     * frontmatter 切分结果。
     *
     * @param frontmatter 顶层标量键值对
     * @param content     正文（frontmatter 之后的部分）
     */
    record Parsed(Map<String, String> frontmatter, String content) {
    }

    /**
     * 解析出来的技能包内容（尚未落库）。
     */
    @Data
    @Builder
    public static class SkillDraft {
        private String name;
        private String description;
        /** SKILL.md 正文（不含 frontmatter） */
        private String content;
        /** 原始 frontmatter 键值对 */
        private Map<String, String> frontmatter;
        private List<SkillResource> resources;
    }

    /**
     * 附属资源。
     *
     * @param path    相对路径，如 {@code notes.md}
     * @param content 文本内容
     */
    public record SkillResource(String path, String content) {
    }
}