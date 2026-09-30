package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.tools.RagTool;
import com.huzhijian.nexusagentweb.tools.ToolCallGuard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagTool 的纯单元测试：重点是**检索必须带 user_id 过滤**（P2-13 修复的越权问题）。
 * <p>
 * 旧实现构造 {@link EmbeddingSearchRequest} 时没有 filter，等于在整张
 * {@code knowledge_embedding} 表上做全局检索 —— 用户 A 能检索到用户 B 的知识库内容。
 * 这几个用例把这条约束钉死，防止以后有人"优化"掉过滤条件。
 * <p>
 * 不依赖 Spring、不访问网络、不连数据库。
 */
class RagToolTest {

    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final PgVectorEmbeddingStore store = mock(PgVectorEmbeddingStore.class);
    private final ToolCallGuard guard = mock(ToolCallGuard.class);

    private final RagTool ragTool = new RagTool(embeddingModel, store, guard);

    @AfterEach
    void clearContext() {
        UserContextHolder.removeUserId();
    }

    private void stubEmbedding() {
        when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(new Embedding(new float[]{0.1f, 0.2f, 0.3f})));
    }

    private void stubEmptyResult() {
        when(store.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
    }

    @Test
    @DisplayName("检索请求必须带 user_id 过滤（防跨用户越权）")
    void searchCarriesUserIdFilter() {
        UserContextHolder.saveId(42L);
        stubEmbedding();
        stubEmptyResult();

        ragTool.ragSearch("s1", "报销流程");

        ArgumentCaptor<EmbeddingSearchRequest> captor =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(captor.capture());

        Filter filter = captor.getValue().filter();
        assertNotNull(filter, "检索必须带过滤条件，否则就是全表检索");
        IsEqualTo eq = assertInstanceOf(IsEqualTo.class, filter);
        assertEquals("user_id", eq.key());
        // 用字符串比较：历史数据里 user_id 可能是 JSON 数字也可能是字符串，::text 两种都能命中
        assertEquals("42", String.valueOf(eq.comparisonValue()));
    }

    @Test
    @DisplayName("没有用户上下文时拒绝检索，绝不退化成全表检索")
    void refusesWhenNoUserContext() {
        UserContextHolder.removeUserId();
        stubEmbedding();

        String result = ragTool.ragSearch("s1", "报销流程");

        assertTrue(result.contains("拒绝"), "应明确返回拒绝原因：" + result);
        verify(store, never()).search(any());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    @DisplayName("切换用户时过滤值跟着变（不能复用上一个用户的条件）")
    void filterFollowsCurrentUser() {
        stubEmbedding();
        stubEmptyResult();

        UserContextHolder.saveId(7L);
        ragTool.ragSearch("s1", "a");

        UserContextHolder.saveId(9L);
        ragTool.ragSearch("s1", "b");

        ArgumentCaptor<EmbeddingSearchRequest> captor =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store, org.mockito.Mockito.times(2)).search(captor.capture());

        List<EmbeddingSearchRequest> requests = captor.getAllValues();
        assertEquals("7", String.valueOf(((IsEqualTo) requests.get(0).filter()).comparisonValue()));
        assertEquals("9", String.valueOf(((IsEqualTo) requests.get(1).filter()).comparisonValue()));
    }

    @Test
    @DisplayName("命中结果按「知识来源 + 片段」拼接，片段之间要有分隔符")
    void formatsMatches() {
        UserContextHolder.saveId(1L);
        stubEmbedding();
        TextSegment segment = TextSegment.from("年假需提前 3 天申请");
        segment.metadata().put("file_name", "员工手册.pdf");
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(
                List.of(new EmbeddingMatch<>(0.9, "id-1",
                        new Embedding(new float[]{0.1f}), segment))));

        String result = ragTool.ragSearch("s1", "年假");

        assertTrue(result.contains("员工手册.pdf"), "应带出知识来源：" + result);
        assertTrue(result.contains("年假需提前 3 天申请"), "应带出原文片段：" + result);
        assertTrue(result.contains("结束"), "片段之间要有分隔符，否则模型分不清边界：" + result);
    }
}
