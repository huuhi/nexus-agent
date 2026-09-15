package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
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
            用于主动保存用户的长期记忆。
            
            【核心要求】
            1. 必须极度精简，去掉废话和冗余。
            2. 严禁包含“用户”或“他/她”等主语（默认主语即为用户本人）。
            3. 仅保留最核心的属性或偏好，无需展开过于具体的细节列表
            4. 记忆内容是供AI后续读取的，请使用客观、简练的断言式短语，尽量一句话说明白。
            
            【长期记忆范畴】
            - 长期稳定的喜好或厌恶（如：喜欢看科幻片、不喜欢吃辣）
            - 长期习惯或作息（如：习惯晚睡、每周五健身）
            - 长期身份信息（如：职业是程序员、现居北京）
            - 长期目标（如：正在准备考研）
            - 持续性的客观事实（如：养了一只英短猫）
            
            如果执行失败，禁止重复尝试！
            """;
    public MemoryTool(EmbeddingModel embeddingModel, PgVectorEmbeddingStore pgVectorEmbeddingStore,UserMemoryService memoryService) {
        this.embeddingModel = embeddingModel;
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.memoryService = memoryService;
    }

    @Tool(name = "search_user_memory",value = "检索用户画像")
    public String searchUserMemory(@P("关键字") String query){
//        取消向量，直接使用模糊搜索
//        SearchMemoryRequest request = SearchMemoryRequest.builder()
//                .maxResult(5)
//                .minScore(0.5F)
//                .embedding(embeddingModel.embed(query).content().vector())
//                .userId(userId)
//                .build();
        try {
            List<UserMemoryVO> memory = memoryService.getMemory(query);
            StringBuilder builder = new StringBuilder();
            memory.forEach(memoryVO -> {
                String content = memoryVO.getContent();
                builder.append(content);
            });
            return builder.toString();
            //            return memoryService.searchMemory(request);
        } catch (Exception e) {
            return "错误，请勿重复"+e.getMessage();
        }
    }

    @Tool(name = "save_user_data",value = SAVE_USER_MEMORY)
    public String saveLongMemory(@P("记忆内容，需要符合核心要求") String content,@P(value = "会话ID",required = false) String sessionId){
        Long userId = UserContextHolder.getUserId();
        log.info("用户ID：{}",userId);
        UserMemory userLongMemory =  UserMemory.builder().content(content).userId(userId).source(sessionId).build();
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
        List<EmbeddingMatch<TextSegment>> matches = null;
        try {
            matches = pgVectorEmbeddingStore.search(request)
                    .matches();
        } catch (Exception e) {
            return e.getMessage();
        }
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
