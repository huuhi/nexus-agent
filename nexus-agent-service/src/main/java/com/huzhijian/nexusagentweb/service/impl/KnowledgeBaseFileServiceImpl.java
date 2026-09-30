package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseFileMapper;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base_file】的数据库操作Service实现
* @createDate 2026-04-16 20:02:41
*/
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeBaseFileServiceImpl extends ServiceImpl<KnowledgeBaseFileMapper, KnowledgeBaseFile>
    implements KnowledgeBaseFileService{
    private final PgVectorEmbeddingStore pgVectorEmbeddingStore;
    private final FileUtils fileUtils;
    private final KnowledgeBaseFileMapper mapper;
    /**
     * 系统默认向量模型（由 `langchain4j-open-ai-spring-boot-starter` 按
     * `langchain4j.open-ai.embedding-model.*` 装配）。
     * <p>
     * <b>入库与检索必须共用它</b> —— 见 {@link #embedding} 上的说明。
     */
    private final EmbeddingModel embeddingModel;


    /**
     * 把文件切分、向量化后写入 `knowledge_embedding`。
     * <p>
     * <b>⚠️ 向量模型口径（P2-13 修复）</b>：这里**必须用系统默认向量模型**，不能按用户配置构造。
     * 原因有两条，第二条更致命：
     * <ol>
     *   <li>旧实现从「用户 API 配置」里找 EMBEDDING 模型，找不到就抛异常 →
     *       **没配过 API Key 的用户建知识库必然失败**（尽管系统已配好向量模型）；</li>
     *   <li>更严重的是**检索用的是系统模型**（{@code RagTool} 注入的 {@code EmbeddingModel}）。
     *       不同向量模型的向量空间不同，就算维度都是 1024 也不可比 ——
     *       用 A 模型入库、B 模型检索，相似度分数毫无意义（表现是"检索结果完全不相关"），
     *       而且**没有任何报错**，是最难排查的那类 bug。</li>
     * </ol>
     * 因此统一为「系统模型入库 + 系统模型检索」。用户自选向量模型是个**伪能力**（自选即串味），
     * 已把 {@code configId}/{@code model} 从方法签名里去掉，避免调用方误以为生效。
     *
     * @param list        待入库文件
     * @param userId      归属用户（写入 metadata，检索时用于隔离）
     * @param knowledgeId 目标知识库 ID
     */
    @Override
    @Async
    @Transactional
    public void embedding(List<SysFile> list, Long userId, int knowledgeId) {
        DocumentSplitter splitter = DocumentSplitters.recursive(850, 150);
        for (SysFile file : list) {
            // ⚠️ 每个文件都要重置：原来 failReason 定义在循环外，
            //    一旦某个文件失败，后面**全部**文件都会被标记成同样的失败原因
            String failReason = "";
            try {
                Document document = fileUtils.getDocument(file);
                document.metadata().put("file_id",file.getId()).put("user_id",userId)
                        .put("file_name",file.getFileName()).put("knowledge",knowledgeId);
                List<TextSegment> textSegments = splitter.split(document);
                List<List<TextSegment>> batches = ListUtil.partition(textSegments, 10);
                for (List<TextSegment> batch : batches) {
                    List<Embedding>  content= embeddingModel.embedAll(batch).content();
//                    必须传 batch，不能传 textSegments：embedAll 只对 batch 做了向量化，
//                    传全量会导致向量与文本错位（历史上这里写的是 textSegments，导致 RAG 检索串味）
                    pgVectorEmbeddingStore.addAll(content,batch);
                    Thread.sleep(200);
                }

            } catch (ClientException e) {
                failReason="参数错误！";
            } catch (InterruptedException e) {
                failReason="线程中断!";
                Thread.currentThread().interrupt();
            } catch (Exception e){
                // 不向外抛：入库失败要落到 knowledge_base_file.fail_reason 上给用户看，
                // 抛出去会让整批文件都没结果（而且这里是 @Async，异常根本传不到调用方）
                log.warn("知识库入库失败 fileId={}: {}", file.getId(), e.getMessage());
                failReason = StringUtils.substring(e.getMessage(), 0, 250);
            }
            KnowledgeBaseFile knowledgeBaseFile = KnowledgeBaseFile.builder()
                    .fileId(file.getId())
                    .knowledgeBaseId(knowledgeId)
                    .status(failReason.isEmpty()?UploadStatus.SUCCESS: UploadStatus.FAILED)
                    .failReason(failReason).build();
            mapper.updateKnowledge(knowledgeBaseFile);
        }

    }



}




