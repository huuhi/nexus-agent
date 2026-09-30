package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.utils.MemoryQueryParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆检索的文本处理（P2-7）单元测试。
 * <p>
 * 覆盖重点是三个「原来会静默出错」的点：整串当关键词、通配符不转义、空输入返回全部。
 */
class MemoryQueryParserTest {

    // ------------------------------------------------------------------
    //  归一化
    // ------------------------------------------------------------------

    @Test
    @DisplayName("normalize：null / 空白一律视为空")
    void normalizeBlank() {
        assertNull(MemoryQueryParser.normalize(null));
        assertNull(MemoryQueryParser.normalize(""));
        assertNull(MemoryQueryParser.normalize("   "));
        assertNull(MemoryQueryParser.normalize("\t\n"));
    }

    @Test
    @DisplayName("normalize：连续空白压成一个空格并去首尾")
    void normalizeCollapse() {
        assertEquals("喜欢 科幻 电影", MemoryQueryParser.normalize("  喜欢   科幻\n电影  "));
    }

    // ------------------------------------------------------------------
    //  关键词切分
    // ------------------------------------------------------------------

    @Test
    @DisplayName("split：按标点/空白切中英文混排")
    void splitMixed() {
        List<String> kws = MemoryQueryParser.split("喜欢看科幻电影，不喜欢吃辣", 6);
        assertEquals(List.of("喜欢看科幻电影", "不喜欢吃辣"), kws);
    }

    @Test
    @DisplayName("split：英文按空白切，保留原大小写（检索用 ILIKE）")
    void splitEnglish() {
        List<String> kws = MemoryQueryParser.split("Python Java  Go", 6);
        assertEquals(List.of("Python", "Java", "Go"), kws);
    }

    @Test
    @DisplayName("split：纯中文无标点（最常见的输入）整串作为一个关键词，绝不退化成空")
    void splitPureChinese() {
        // 这是老实现最典型的失败场景：模型丢一整句话进来，LIKE '%喜欢吃什么%' 命中不了「不吃辣」
        List<String> kws = MemoryQueryParser.split("喜欢吃什么", 6);
        assertEquals(List.of("喜欢吃什么"), kws);
    }

    @Test
    @DisplayName("split：单字碎片被丢弃（噪声太大），但整串兜底保证不为空")
    void splitSingleCharFallback() {
        assertEquals(List.of("猫"), MemoryQueryParser.split("猫", 6));
        // 全是单字时，用整个归一化串兜底
        assertEquals(List.of("a b c"), MemoryQueryParser.split("a b c", 6));
    }

    @Test
    @DisplayName("split：去重保序，且不超过 maxKeywords")
    void splitCapAndDistinct() {
        List<String> kws = MemoryQueryParser.split("科幻 电影 科幻 小说 音乐", 3);
        assertEquals(List.of("科幻", "电影", "小说"), kws);
    }

    @Test
    @DisplayName("split：空白输入返回空列表（调用方据此改为浏览最新，而不是查全部）")
    void splitEmpty() {
        assertTrue(MemoryQueryParser.split(null, 6).isEmpty());
        assertTrue(MemoryQueryParser.split("  ", 6).isEmpty());
    }

    @Test
    @DisplayName("contentKey：去掉全部空白并转小写 —— 必须与 SQL 侧 regexp_replace+lower 一致")
    void contentKey() {
        assertEquals("喜欢科幻", MemoryQueryParser.contentKey("喜欢 科幻"));
        assertEquals("喜欢科幻", MemoryQueryParser.contentKey("喜欢  科幻"));
        assertEquals("scifi", MemoryQueryParser.contentKey(" SciFi "));
        assertEquals("", MemoryQueryParser.contentKey(null));
        assertEquals("", MemoryQueryParser.contentKey("   "));
    }

    // ------------------------------------------------------------------
    //  LIKE 转义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("escapeLike：百分号、下划线、反斜杠全部转义，防止关键词被当成通配符")
    void escapeLike() {
        assertEquals("a\\%b", MemoryQueryParser.escapeLike("a%b"));
        assertEquals("a\\_b", MemoryQueryParser.escapeLike("a_b"));
        assertEquals("a\\\\b", MemoryQueryParser.escapeLike("a\\b"));
    }

    @Test
    @DisplayName("likePattern：生成百分号包裹的关键词，单个百分号不会退化成匹配全部")
    void likePattern() {
        assertEquals("%科幻%", MemoryQueryParser.likePattern("科幻"));
        // 老实现里 key=% 会生成 %%，即「匹配全部记忆」
        assertEquals("%\\%%", MemoryQueryParser.likePattern("%"));
    }

    // ------------------------------------------------------------------
    //  命中计数（排序依据）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("countMatches：大小写不敏感，按去重后的关键词个数计")
    void countMatches() {
        assertEquals(2, MemoryQueryParser.countMatches("喜欢看SciFi电影", List.of("scifi", "电影")));
        assertEquals(1, MemoryQueryParser.countMatches("喜欢看电影", List.of("scifi", "电影")));
        assertEquals(0, MemoryQueryParser.countMatches("喜欢看电影", List.of("不吃辣")));
        assertEquals(0, MemoryQueryParser.countMatches(null, List.of("电影")));
        assertEquals(0, MemoryQueryParser.countMatches("喜欢看电影", List.of()));
    }
}
