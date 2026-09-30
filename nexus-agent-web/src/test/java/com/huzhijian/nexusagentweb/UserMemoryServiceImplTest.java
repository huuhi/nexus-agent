package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.UserMemoryMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.UserMemoryServiceImpl;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆检索/写入（P2-7）的单元测试：mock 掉 Mapper，不连数据库。
 * <p>
 * SQL 本身（ILIKE / pg_trgm similarity）无法在这里验证，
 * 只能靠 {@code docs/sql/006_add_user_memory_trgm_index.sql} 里的验证查询人工确认；
 * 这里覆盖的是「调用哪条 SQL、参数对不对、降级逻辑对不对」这些分支。
 */
class UserMemoryServiceImplTest {

    private static final Long UID = 1L;

    private UserMemoryMapper mapper;
    private AgentProperties props;
    private UserMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserMemoryMapper.class);
        props = new AgentProperties();
        service = new UserMemoryServiceImpl(mapper, props);
        UserContextHolder.saveId(UID);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    private static UserMemory m(Long id, String content) {
        return UserMemory.builder().id(id).userId(UID).content(content).build();
    }

    // ------------------------------------------------------------------
    //  检索：分支选择
    // ------------------------------------------------------------------

    @Test
    @DisplayName("检索词为空：走「按最新取」而不是返回全部记忆")
    void blankKeyReturnsLatest() {
        when(mapper.latestByUser(UID, 20)).thenReturn(List.of(m(3L, "喜欢科幻"), m(2L, "不吃辣")));

        List<UserMemoryVO> res = service.getMemory("   ");

        assertEquals(2, res.size());
        verify(mapper).latestByUser(UID, 20);
        verify(mapper, never()).searchByKeywords(anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("检索词非空：拆成多个关键词，各自包成 %kw%")
    void keywordsSplit() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of());

        service.getMemory("科幻，电影");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).searchByKeywords(eq(UID), captor.capture(), eq(80));
        assertEquals(List.of("%科幻%", "%电影%"), captor.getValue());
    }

    @Test
    @DisplayName("关键词里的通配符会被转义：单个 % 不会退化成「匹配全部记忆」")
    void wildcardEscaped() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of());

        // 老实现：key="%" → LIKE '%%' → 命中全部；且切不出关键词时 old code 直接查全部
        service.getMemory("%");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).searchByKeywords(eq(UID), captor.capture(), eq(80));
        assertEquals(List.of("%\\%%"), captor.getValue());
    }

    @Test
    @DisplayName("排序：命中关键词多的排前面，命中数相同则新的排前面")
    void rankByHitCountThenRecency() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt()))
                .thenReturn(List.of(m(1L, "不喜欢吃辣"), m(2L, "喜欢看科幻电影")));

        List<UserMemoryVO> res = service.getMemory("科幻，电影");

        assertEquals(2, res.size());
        assertEquals(2L, res.get(0).getId(), "命中两个关键词的应排最前");
        assertEquals(1L, res.get(1).getId());
    }

    @Test
    @DisplayName("去重：归一化后内容相同的只保留一条（保留排序更靠前的）")
    void dedupSameContent() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt()))
                .thenReturn(List.of(m(1L, "喜欢科幻"), m(2L, "喜欢 科幻")));

        List<UserMemoryVO> res = service.getMemory("科幻");

        assertEquals(1, res.size());
        assertEquals(2L, res.get(0).getId(), "命中数相同时新的优先，去重保留它");
    }

    @Test
    @DisplayName("结果条数受 maxResults 限制（避免把全部记忆塞进提示词）")
    void resultCappedByMaxResults() {
        props.getMemory().setMaxResults(2);
        when(mapper.searchByKeywords(anyLong(), any(), anyInt()))
                .thenReturn(List.of(m(1L, "科幻"), m(2L, "电影"), m(3L, "音乐")));

        assertEquals(2, service.getMemory("科幻 电影 音乐").size());
    }

    // ------------------------------------------------------------------
    //  检索：pg_trgm 兜底与降级
    // ------------------------------------------------------------------

    @Test
    @DisplayName("字面匹配零命中时，用 pg_trgm 的 similarity 兜底")
    void fuzzyFallback() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(mapper.searchBySimilarity(eq(UID), eq("喜欢看科幻电影"), anyDouble(), eq(20)))
                .thenReturn(List.of(m(9L, "喜欢看科幻片")));

        List<UserMemoryVO> res = service.getMemory("喜欢看科幻电影");

        assertEquals(1, res.size());
        assertEquals("喜欢看科幻片", res.get(0).getContent());
        verify(mapper).searchBySimilarity(eq(UID), eq("喜欢看科幻电影"), eq(0.15), eq(20));
    }

    @Test
    @DisplayName("字面匹配已有命中时不再做模糊检索（避免噪声污染结果）")
    void noFuzzyWhenLiteralHit() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of(m(1L, "喜欢科幻")));
        service.getMemory("科幻");
        verify(mapper, never()).searchBySimilarity(anyLong(), anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("关闭 fuzzy 后不再调用 similarity")
    void fuzzyDisabled() {
        props.getMemory().setFuzzy(false);
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of());

        assertTrue(service.getMemory("科幻").isEmpty());
        verify(mapper, never()).searchBySimilarity(anyLong(), anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("没装 pg_trgm 扩展：首次失败后永久降级，不抛异常、不反复重试")
    void fuzzyDegradesWhenExtensionMissing() {
        when(mapper.searchByKeywords(anyLong(), any(), anyInt())).thenReturn(List.of());
        when(mapper.searchBySimilarity(anyLong(), anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("function similarity(text, character varying) does not exist"));

        assertTrue(service.getMemory("科幻").isEmpty());
        assertTrue(service.getMemory("电影").isEmpty());

        // 只在第一次尝试，之后直接跳过
        verify(mapper, times(1)).searchBySimilarity(anyLong(), anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("未登录：明确抛未授权，而不是像原来那样静默返回空（user_id = NULL 恒不成立）")
    void unauthorizedWhenNoUser() {
        UserContextHolder.removeUserId();
        assertThrows(UnauthorizedException.class, () -> service.getMemory("科幻"));
    }

    // ------------------------------------------------------------------
    //  写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写入：空内容直接丢弃")
    void saveBlankSkipped() {
        service.saveMemory(UserMemory.builder().userId(UID).content("   ").build());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("写入：超长内容按 maxContentLength 截断")
    void saveTruncated() {
        props.getMemory().setMaxContentLength(5);
        UserMemory mem = UserMemory.builder().userId(UID).content("1234567890").build();

        service.saveMemory(mem);

        assertEquals("12345", mem.getContent());
        verify(mapper).insert(mem);
    }

    @Test
    @DisplayName("写入：归一化（去掉多余空白）后完全重复的丢弃")
    void saveExactDuplicateSkipped() {
        // 入参多打了个空格，归一化后应为「喜欢 科幻」，用它去查重
        when(mapper.countByNormalizedContent(UID, "喜欢 科幻")).thenReturn(1L);
        UserMemory mem = UserMemory.builder().userId(UID).content("喜欢  科幻").build();

        service.saveMemory(mem);

        // 入参多打了个空格，传给 SQL 的应当是归一化后的「喜欢 科幻」
        verify(mapper).countByNormalizedContent(UID, "喜欢 科幻");
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("写入：trigram 相似度达到阈值也视为重复（依赖 pg_trgm，失败则放行）")
    void saveSimilarDuplicateSkipped() {
        when(mapper.countByNormalizedContent(anyLong(), anyString())).thenReturn(0L);
        when(mapper.countSimilar(eq(UID), eq("喜欢看科幻片"), eq(0.85))).thenReturn(1L);

        service.saveMemory(UserMemory.builder().userId(UID).content("喜欢看科幻片").build());
        verify(mapper, never()).insert(any());

        // 相似度查询不可用（没装扩展）时，宁可写进去也不要丢内容
        when(mapper.countSimilar(anyLong(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("similarity does not exist"));
        UserMemory mem = UserMemory.builder().userId(UID).content("喜欢看科幻片").build();
        service.saveMemory(mem);
        verify(mapper).insert(mem);
    }

    @Test
    @DisplayName("写入：正常内容落库")
    void saveNormal() {
        when(mapper.countByNormalizedContent(anyLong(), anyString())).thenReturn(0L);
        when(mapper.countSimilar(anyLong(), anyString(), anyDouble())).thenReturn(0L);
        UserMemory mem = UserMemory.builder().userId(UID).content("喜欢科幻").build();

        service.saveMemory(mem);

        verify(mapper).insert(mem);
        assertEquals("喜欢科幻", mem.getContent());
    }

    // ------------------------------------------------------------------
    //  删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除：不是自己的记忆按「不存在」处理（越权修复不能回退）")
    void deleteNotFound() {
        when(mapper.delete(any())).thenReturn(0);
        assertThrows(NotFoundException.class, () -> service.deleteById(99L));
    }

    @Test
    @DisplayName("删除：未登录直接拒绝")
    void deleteUnauthorized() {
        UserContextHolder.removeUserId();
        assertThrows(UnauthorizedException.class, () -> service.deleteById(1L));
    }
}
