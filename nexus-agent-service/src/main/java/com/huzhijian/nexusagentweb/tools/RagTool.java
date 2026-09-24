package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/1
 * 说明: 知识库检索工具。
 * <p>
 * 本工具集是**按需启用**的：只有请求里 {@code enableRag=true} 时才注册给模型，
 * 由 {@link #enabled(ToolSelection)} 声明。启用条件不再写在 ChatContextFactory 里。
 */
@Component
@Slf4j
public class RagTool implements AgentToolSet {

    @Override
    public String key() {
        return "rag";
    }

    @Override
    public String description() {
        return "知识库检索：按需开启，用于回答专业问题";
    }

    @Override
    public boolean enabled(ToolSelection selection) {
        return selection.ragEnabled();
    }

    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore pgVectorEmbeddingStore;
    private final ToolCallGuard toolCallGuard;

    public RagTool(EmbeddingModel embeddingModel, PgVectorEmbeddingStore pgVectorEmbeddingStore,
                   ToolCallGuard toolCallGuard) {
        this.embeddingModel = embeddingModel;
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.toolCallGuard = toolCallGuard;
    }

    /**
     * 工具名显式声明为 {@code rag_search}，与历史契约保持一致（不要改成默认的方法名 ragSearch，
     * 那会让已上线的提示词/前端匹配失效）。
     */
    @Tool(name = "rag_search", value = "检索知识库以回答专业问题")
    public String ragSearch(@ToolMemoryId Object memoryId, @P("查询语句,提取关键词查询") String query) {
        // 同一会话用同一关键词反复检索没有意义（结果一致），且每次都要花 embedding 调用
        String blocked = toolCallGuard.interceptText(memoryId, "rag_search",
                ToolCallGuard.fingerprint(query));
        if (blocked != null) {
            return blocked;
        }
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .query(query)
                    .maxResults(3).minScore(0.7)
                    .queryEmbedding(embeddingModel.embed(query).content())
                    .build();
            log.debug("知识库检索：{}", request.query());
            List<EmbeddingMatch<TextSegment>> matches = pgVectorEmbeddingStore.search(request).matches();
            if (matches == null || matches.isEmpty()) {
                return "知识库中未查询到相关知识片段，你可以修改关键词再次尝试查询";
            }
            StringBuilder result = new StringBuilder("以下是检索到的资料:");
            matches.forEach(match -> {
                TextSegment embedded = match.embedded();
                result.append("知识来源:").append(embedded.metadata().getString("file_name"));
                result.append(embedded.text());
                result.append("--------结束---------");
            });
            return result.toString();
        } catch (Exception e) {
//            不抛出：把失败原因作为工具结果回给模型，避免整条流因为检索失败而中断
            log.warn("知识库检索失败: {}", e.getMessage());
            return "知识库检索失败：" + e.getMessage() + "，请勿重复检索同一关键词";
        }
    }
}
