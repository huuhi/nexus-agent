package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Mapper
* @createDate 2026-05-10 21:29:23
* @Entity com.huzhijian.nexusagentweb.domain.UserMemory
*
* <p>P2-7：原来的 {@code search(SearchMemoryRequest)} 是坏的 —— 它 SELECT 了表里
* 并不存在的 {@code embedding} / {@code category} 两列，一调用必然报错。
* 决策 D4 定为「先 pg_trgm，不上 pgvector」，故整条向量路径已删除
* （含 {@code SearchMemoryRequest} / {@code MemorySearchResult} / {@code searchMemory}）；
* 将来要恢复语义检索，从 git 历史找回这段 SQL 再加列即可。
*/
public interface UserMemoryMapper extends BaseMapper<UserMemory> {

    /**
     * 多关键词字面匹配（OR）。{@code patterns} 由
     * {@link com.huzhijian.nexusagentweb.utils.MemoryQueryParser#likePattern} 生成。
     */
    List<UserMemory> searchByKeywords(@Param("userId") Long userId,
                                      @Param("patterns") List<String> patterns,
                                      @Param("limit") int limit);

    /** 无检索词时按最新取，避免把全部记忆一次性塞进提示词 */
    List<UserMemory> latestByUser(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * pg_trgm 相似检索。需要 {@code CREATE EXTENSION pg_trgm}
     * （见 {@code docs/sql/006_add_user_memory_trgm_index.sql}）。
     */
    List<UserMemory> searchBySimilarity(@Param("userId") Long userId,
                                        @Param("query") String query,
                                        @Param("minScore") double minScore,
                                        @Param("limit") int limit);

    /** 归一化后完全一致的条数（写入去重用） */
    long countByNormalizedContent(@Param("userId") Long userId, @Param("content") String content);

    /** trigram 相似度达到阈值的条数（写入去重用，依赖 pg_trgm） */
    long countSimilar(@Param("userId") Long userId,
                      @Param("content") String content,
                      @Param("threshold") double threshold);
}
