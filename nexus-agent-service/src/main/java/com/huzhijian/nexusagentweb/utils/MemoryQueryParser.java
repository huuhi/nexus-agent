package com.huzhijian.nexusagentweb.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 长期记忆检索用的文本处理（P2-7）。
 * <p>
 * 这些逻辑原来是散在 {@code UserMemoryServiceImpl.getMemory} 里的一句
 * {@code .like(key != null, "content", key)}：
 * <ul>
 *   <li>整串当成一个关键词 —— 模型丢一句"用户喜欢吃什么口味的菜"进来就什么都匹配不到；</li>
 *   <li>没有转义 —— 关键词里带 {@code %} / {@code _} 会变成通配符，甚至退化成"匹配全部"；</li>
 *   <li>没有条数上限、没有排序、没有去重 —— 有多少条返回多少条，全塞进提示词。</li>
 * </ul>
 * 这里把可纯函数化的部分抽出来，方便单测覆盖（见 {@code MemoryQueryParserTest}）。
 * <p>
 * <b>关于"为什么不做语义检索"</b>：决策 D4 选定先上 pg_trgm（见
 * {@code docs/sql/006_add_user_memory_trgm_index.sql}）。
 * trigram 解决的是<b>字面重叠</b>（"喜欢看科幻电影" ↔ "喜欢看科幻片"），
 * 解决不了<b>语义相似</b>（"喜欢吃什么" ↔ "不吃辣"）—— 后者必须靠向量，留到记忆量上来之后。
 */
public final class MemoryQueryParser {

    private MemoryQueryParser() {
    }

    /**
     * 关键词分隔符：连续的中英文/数字之外的一切（空格、标点、换行…）。
     * 中文没有空格，所以只能靠标点切；切不动时整串当一个关键词（见 {@link #split}）。
     */
    private static final Pattern SEPARATOR = Pattern.compile("[^0-9A-Za-z\\u4e00-\\u9fa5]+");

    /** 连续空白，用于归一化 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** LIKE 里需要转义的三个字符（PG/MySQL 通用，ESCAPE 用反斜杠） */
    private static final Pattern LIKE_SPECIAL = Pattern.compile("([\\\\%_])");

    /**
     * 归一化：去首尾空白、把连续空白压成一个空格。全空（含 null）返回 {@code null}。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = WHITESPACE.matcher(raw).replaceAll(" ").trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 把用户的检索串切成关键词列表。
     * <p>
     * 规则：先归一化 → 按非中英文数字的标点/空白切分 → 丢掉长度 &lt; 2 的碎片
     * （单个字/字母噪声太大）→ 去重保序 → 最多保留 {@code maxKeywords} 个。
     * <br>
     * 若切完为空但原串非空（典型：纯中文且没有标点，如"喜欢吃什么"），
     * 就把整串当一个关键词，保证不会退化成"查全部"。
     *
     * @param raw         原始检索串，允许 null / 空白
     * @param maxKeywords 最多保留几个关键词；&lt;=0 时按 1 处理
     * @return 关键词列表，可能为空（仅在 raw 为空白时）
     */
    public static List<String> split(String raw, int maxKeywords) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return List.of();
        }
        int cap = Math.max(1, maxKeywords);

        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String token : SEPARATOR.split(normalized)) {
            if (token.length() >= 2) {
                keywords.add(token);
            }
            if (keywords.size() >= cap) {
                break;
            }
        }
        if (keywords.isEmpty()) {
            keywords.add(normalized);
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 去重/比较用的内容键：去掉**全部空白**并转小写。
     * <p>
     * 必须与 SQL 侧保持一致 —— {@code UserMemoryMapper.xml#countByNormalizedContent} 用的是
     * {@code lower(regexp_replace(content, '\s+', '', 'g'))}。
     * 若两边不一致（例如 Java 只压缩空白、SQL 去掉空白），
     * 就会出现「Java 判不重复、SQL 判重复」或反之的静默差异。
     */
    public static String contentKey(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return WHITESPACE.matcher(content).replaceAll("").toLowerCase();
    }

    /**
     * 转义 LIKE 通配符（{@code %}、{@code _}、{@code \}），防止关键词被当成模式。
     */
    public static String escapeLike(String s) {
        return LIKE_SPECIAL.matcher(s).replaceAll("\\\\$1");
    }

    /**
     * 生成 {@code ILIKE} 用的包含匹配模式：{@code %关键词%}。
     * <p>
     * 生成的 SQL 片段必须配 {@code ESCAPE '\'} 使用
     * （见 {@code UserMemoryMapper.xml#searchByKeywords}）。
     */
    public static String likePattern(String keyword) {
        return "%" + escapeLike(keyword) + "%";
    }

    /**
     * 统计一段内容命中了多少个关键词（大小写不敏感），用于结果排序。
     *
     * @return 命中的关键词个数（去重计），0 表示都没命中
     */
    public static int countMatches(String content, List<String> keywords) {
        if (content == null || content.isEmpty() || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase();
        int hits = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                hits++;
            }
        }
        return hits;
    }
}
