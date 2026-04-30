package com.huzhijian.nexusagentweb.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/1
 * 说明:
 */
@Component
@Slf4j
public class RagTool {
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore pgVectorEmbeddingStore;

    public RagTool(EmbeddingModel embeddingModel, PgVectorEmbeddingStore pgVectorEmbeddingStore) {
        this.embeddingModel = embeddingModel;
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
    }

    @Tool(name="rag_search",value = "检索知识库以回答专业问题")
    public String ragSearch(@P("查询语句,提取关键词查询")String query){
        StringBuilder result=new StringBuilder();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .query(query)
                .maxResults(3).minScore(0.7)
                .queryEmbedding(embeddingModel.embed(query).content())
                .build();
        log.info("查询：{}",request.query());
        List<EmbeddingMatch<TextSegment>> matches = pgVectorEmbeddingStore.search(request)
                .matches();
        if (matches==null||matches.isEmpty()){
            return "知识库中未查询到修改知识片段，你可以修改关键词再次尝试查询";
        }
        result.append("以下是检索到的资料:");
        matches.forEach(t->{
            TextSegment embedded = t.embedded();
            result.append("知识来源:").append(embedded.metadata().getString("file_name"));
            result.append(t.embedded().text());
            result.append("--------结束---------");
        });
        return result.toString();
    }


}
