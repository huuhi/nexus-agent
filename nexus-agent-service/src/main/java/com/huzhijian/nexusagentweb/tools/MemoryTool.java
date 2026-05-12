package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.dto.SearchMemoryRequest;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
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
public class MemoryTool {
    private final EmbeddingModel embeddingModel;
    private final PgVectorEmbeddingStore pgVectorEmbeddingStore;
    private final UserMemoryService memoryService;
    private final String SAVE_USER_MEMORY= """
            主动保存用户长期记忆
            长期记忆包括：
            - 长期稳定的喜好
            - 长期习惯
            - 长期身份信息
            - 长期目标
            - 持续性的事实
            如果失败，禁止重复执行！
            """;
    public MemoryTool(EmbeddingModel embeddingModel, PgVectorEmbeddingStore pgVectorEmbeddingStore,UserMemoryService memoryService) {
        this.embeddingModel = embeddingModel;
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.memoryService = memoryService;
    }

    @Tool(name = "search_user_memory",value = "检索用户画像")
    public String searchUserMemory(@P("关键字") String query){
        Long userId = UserContextHolder.getUserId();
        SearchMemoryRequest request = SearchMemoryRequest.builder()
                .maxResult(5)
                .minScore(0.5F)
                .embedding(embeddingModel.embed(query).content().vector())
                .userId(userId)
                .build();
        try {
            return memoryService.searchMemory(request);
        } catch (Exception e) {
            return "错误，请勿重复"+e.getMessage();
        }
    }

    @Tool(name = "save_user_data",value = SAVE_USER_MEMORY)
    public String saveLongMemory(@P("内容，精简之后的内容") String content,@P("信息分类") String category){
        Long userId = UserContextHolder.getUserId();
        log.info("用户ID：{}",userId);
        UserMemory userLongMemory =  UserMemory.builder().content(content).userId(userId).category(category).build();
        try {
            memoryService.saveMemory(userLongMemory);
        } catch (Exception e) {
            return "error:"+e.getMessage();
        }
        return "ok";
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
