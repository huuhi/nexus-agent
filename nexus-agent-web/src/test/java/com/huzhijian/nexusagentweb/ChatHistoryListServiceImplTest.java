package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.ChatHistoryListMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.service.impl.ChatHistoryListServiceImpl;
import com.huzhijian.nexusagentweb.vo.ChatSessionSearchVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static com.huzhijian.nexusagentweb.service.impl.ChatHistoryListServiceImpl.MATCH_CONTENT;
import static com.huzhijian.nexusagentweb.service.impl.ChatHistoryListServiceImpl.MATCH_TITLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话重命名 + 会话搜索（P3-1 补）的单元测试：mock 掉 Mapper 与 ChatMemoryService，不连数据库。
 * <p>
 * SQL 本身（jsonb 抽正文 + ILIKE）没法在这里验证，只能靠 {@code docs/sql} 里的验证查询人工确认；
 * 这里覆盖的是「参数校验、越权、通配符转义、合并去重与排序」这些 Java 侧分支。
 */
class ChatHistoryListServiceImplTest {

    private static final Long UID = 7L;
    private static final String SID_A = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    private static final String SID_B = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private ChatHistoryListMapper mapper;
    private ChatMemoryService chatMemoryService;
    private AgentProperties props;
    private ChatHistoryListServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatHistoryListMapper.class);
        chatMemoryService = mock(ChatMemoryService.class);
        props = new AgentProperties();
        service = new ChatHistoryListServiceImpl(null, mapper, chatMemoryService, props);
        UserContextHolder.saveId(UID);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    private static ChatHistoryList session(String sid, String title, Date updateTime) {
        return ChatHistoryList.builder().sessionId(sid).userId(UID).title(title)
                .createTime(updateTime).updateTime(updateTime).build();
    }

    private static ChatMemorySearchHit hit(String sid, String text, Date at) {
        return ChatMemorySearchHit.builder().sessionId(sid).snippet(text).createAt(at).build();
    }

    // ------------------------------------------------------------------
    //  重命名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重命名：正常更新会带上 user_id（不带就是越权写）")
    void renameOk() {
        when(mapper.updateTitleBySessionAndUserId(eq(SID_A), anyString(), anyLong())).thenReturn(1);

        service.rename(SID_A, "  新的标题  ");

        verify(mapper).updateTitleBySessionAndUserId(SID_A, "新的标题", UID);
    }

    @Test
    @DisplayName("重命名：影响 0 行说明会话不存在或不是本人的，抛 NotFound")
    void renameNotFound() {
        when(mapper.updateTitleBySessionAndUserId(anyString(), anyString(), anyLong())).thenReturn(0);

        assertThrows(NotFoundException.class, () -> service.rename(SID_A, "新标题"));
    }

    @Test
    @DisplayName("重命名：不是合法 UUID 直接拒绝，不能让它落到 SQL 里（::uuid 会抛异常变 500）")
    void renameBadUuid() {
        assertThrows(ValidationException.class, () -> service.rename("not-a-uuid", "新标题"));
        verify(mapper, never()).updateTitleBySessionAndUserId(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("重命名：空白标题拒绝")
    void renameBlankTitle() {
        assertThrows(ValidationException.class, () -> service.rename(SID_A, "   "));
        assertThrows(ValidationException.class, () -> service.rename(SID_A, null));
        verify(mapper, never()).updateTitleBySessionAndUserId(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("重命名：标题超长拒绝（varchar(255) 装得下，但没有可读性）")
    void renameTooLong() {
        String tooLong = "标".repeat(101);
        assertThrows(ValidationException.class, () -> service.rename(SID_A, tooLong));
        verify(mapper, never()).updateTitleBySessionAndUserId(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("重命名：未登录直接拒绝")
    void renameUnauthorized() {
        UserContextHolder.removeUserId();
        assertThrows(UnauthorizedException.class, () -> service.rename(SID_A, "新标题"));
    }

    // ------------------------------------------------------------------
    //  搜索：参数校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("搜索：未登录直接拒绝（不能退化成查 user_id=NULL）")
    void searchUnauthorized() {
        UserContextHolder.removeUserId();
        assertThrows(UnauthorizedException.class, () -> service.search("科幻"));
    }

    @Test
    @DisplayName("搜索：空关键词拒绝，不查库")
    void searchBlank() {
        assertThrows(ValidationException.class, () -> service.search("   "));
        assertThrows(ValidationException.class, () -> service.search(null));
        verify(chatMemoryService, never()).searchHits(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("搜索：关键词里的 % 会被转义 —— 否则一个 % 就退化成「匹配全部消息」")
    void searchWildcardEscaped() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        service.search("%");

        verify(chatMemoryService).searchHits(eq(UID), eq("%\\%%"), eq(300));
    }

    @Test
    @DisplayName("搜索：下划线与反斜杠同样转义")
    void searchUnderscoreEscaped() {
        when(mapper.selectList(any())).thenReturn(List.of());
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        service.search("a_b\\c");

        verify(chatMemoryService).searchHits(eq(UID), eq("%a\\_b\\\\c%"), eq(300));
    }

    // ------------------------------------------------------------------
    //  搜索：结果合并
    // ------------------------------------------------------------------

    @Test
    @DisplayName("搜索：标题命中 —— matchType=TITLE，不带 snippet")
    void searchTitleHit() {
        when(mapper.selectList(any())).thenReturn(List.of(
                session(SID_A, "聊聊科幻电影", new Date(2000)),
                session(SID_B, "今天吃什么", new Date(1000))));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        List<ChatSessionSearchVO> res = service.search("科幻");

        assertEquals(1, res.size());
        assertEquals(SID_A, res.get(0).getSessionId());
        assertEquals(MATCH_TITLE, res.get(0).getMatchType());
        assertNull(res.get(0).getSnippet());
        assertEquals(0, res.get(0).getHitCount());
    }

    @Test
    @DisplayName("搜索：标题匹配忽略大小写")
    void searchTitleIgnoreCase() {
        when(mapper.selectList(any())).thenReturn(List.of(session(SID_A, "Python 入门", new Date(2000))));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        assertEquals(1, service.search("python").size());
    }

    @Test
    @DisplayName("搜索：内容命中 —— 补上标题、给出片段、统计命中条数")
    void searchContentHit() {
        Date at = new Date(3000);
        when(mapper.selectList(any())).thenReturn(List.of(session(SID_B, "没有标题命中的会话", at)));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit(SID_B, "我推荐你看《三体》这部科幻小说", at),
                        hit(SID_B, "另外还有科幻电影也很好看", new Date(2000))));

        List<ChatSessionSearchVO> res = service.search("科幻");

        assertEquals(1, res.size());
        ChatSessionSearchVO vo = res.get(0);
        assertEquals(SID_B, vo.getSessionId());
        assertEquals(MATCH_CONTENT, vo.getMatchType());
        assertEquals("没有标题命中的会话", vo.getTitle());
        assertEquals(2, vo.getHitCount(), "同一会话命中两条消息应累加");
        assertTrue(vo.getSnippet().contains("科幻"));
        assertEquals(at, vo.getUpdateTime());
    }

    @Test
    @DisplayName("搜索：标题已命中的会话不再作为内容命中重复出现")
    void searchNoDuplicate() {
        Date at = new Date(3000);
        when(mapper.selectList(any())).thenReturn(List.of(session(SID_A, "科幻电影推荐", at)));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit(SID_A, "正文里也有科幻两个字", at)));

        List<ChatSessionSearchVO> res = service.search("科幻");

        assertEquals(1, res.size());
        assertEquals(MATCH_TITLE, res.get(0).getMatchType());
    }

    @Test
    @DisplayName("搜索：标题命中整体排在内容命中前面")
    void searchTitleFirst() {
        Date now = new Date(9000);
        Date old = new Date(1000);
        when(mapper.selectList(any())).thenReturn(List.of(
                session(SID_B, "无关标题", now),
                session(SID_A, "科幻杂谈", old)));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit(SID_B, "正文提到科幻", now)));

        List<ChatSessionSearchVO> res = service.search("科幻");

        assertEquals(2, res.size());
        assertEquals(MATCH_TITLE, res.get(0).getMatchType());
        assertEquals(SID_A, res.get(0).getSessionId());
        assertEquals(MATCH_CONTENT, res.get(1).getMatchType());
    }

    @Test
    @DisplayName("搜索：内容命中的会话没有标题记录时，用命中消息的时间兜底")
    void searchFallbackTime() {
        Date at = new Date(5000);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(hit(SID_A, "正文提到科幻", at)));

        List<ChatSessionSearchVO> res = service.search("科幻");

        assertEquals(1, res.size());
        assertNull(res.get(0).getTitle());
        assertEquals(at, res.get(0).getUpdateTime());
    }

    @Test
    @DisplayName("搜索：结果条数受 searchMaxSessions 限制")
    void searchCapped() {
        props.getHistory().setSearchMaxSessions(1);
        when(mapper.selectList(any())).thenReturn(List.of(
                session(SID_A, "科幻一", new Date(3000)),
                session(SID_B, "科幻二", new Date(2000))));
        when(chatMemoryService.searchHits(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        assertEquals(1, service.search("科幻").size());
    }

    // ------------------------------------------------------------------
    //  两个纯函数
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toLikePattern：两侧包 %，中间的通配符全部转义")
    void likePattern() {
        assertEquals("%abc%", ChatHistoryListServiceImpl.toLikePattern("abc"));
        assertEquals("%\\%%", ChatHistoryListServiceImpl.toLikePattern("%"));
        assertEquals("%a\\_b%", ChatHistoryListServiceImpl.toLikePattern("a_b"));
        assertEquals("%a\\\\b%", ChatHistoryListServiceImpl.toLikePattern("a\\b"));
    }

    @Test
    @DisplayName("snippetAround：截取关键词附近的一段，两端用省略号")
    void snippet() {
        String text = "前面有很长很长的一段铺垫内容，中间出现了关键词，后面还有很多话要说";

        String s = ChatHistoryListServiceImpl.snippetAround(text, "关键词", 5);

        assertTrue(s.startsWith("…"), "截断了前面应带省略号");
        assertTrue(s.endsWith("…"), "截断了后面应带省略号");
        assertTrue(s.contains("关键词"));
        assertTrue(s.length() < text.length());
    }

    @Test
    @DisplayName("snippetAround：空文本返回空串，不抛异常")
    void snippetBlank() {
        assertEquals("", ChatHistoryListServiceImpl.snippetAround(null, "关键词", 5));
        assertEquals("", ChatHistoryListServiceImpl.snippetAround("  ", "关键词", 5));
    }
}
