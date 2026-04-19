package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseFileMapper;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base_file】的数据库操作Service实现
* @createDate 2026-04-16 20:02:41
*/
@Service
@Slf4j
public class KnowledgeBaseFileServiceImpl extends ServiceImpl<KnowledgeBaseFileMapper, KnowledgeBaseFile>
    implements KnowledgeBaseFileService{
    private final PgVectorEmbeddingStore pgVectorEmbeddingStore;
    private final FileUtils fileUtils;
    private final KnowledgeBaseFileMapper mapper;
    private final EmbeddingModel model;
    public KnowledgeBaseFileServiceImpl(PgVectorEmbeddingStore pgVectorEmbeddingStore,  FileUtils fileUtils, KnowledgeBaseFileMapper mapper, EmbeddingModel model) {
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.fileUtils = fileUtils;
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    @Async
    @Transactional
    public void embedding(List<SysFile> list, Long userId, KnowledgeBase knowledge) {
        DocumentSplitter splitter = DocumentSplitters.recursive(850, 150);
        for (SysFile file : list) {

            String failReason="";
            try {
                Document document = fileUtils.getDocument(file);
                document.metadata().put("file_id",file.getId()).put("user_id",userId)
                        .put("file_name",file.getFileName()).put("knowledge",knowledge.getId());
                List<TextSegment> textSegments = splitter.split(document);
                List<List<TextSegment>> batches = ListUtil.partition(textSegments, 10);
                for (List<TextSegment> batch : batches) {
                    List<Embedding>  content= model.embedAll(batch).content();
                    pgVectorEmbeddingStore.addAll(content,textSegments);
                    Thread.sleep(200);
                }

            } catch (IOException e) {
                failReason=e.getMessage().substring(0,250);
            } catch (ClientException e) {
                failReason="参数错误！";
            } catch (InterruptedException e) {
                failReason="线程中断!";
                Thread.currentThread().interrupt();
            }
            KnowledgeBaseFile knowledgeBaseFile = KnowledgeBaseFile.builder()
                    .fileId(file.getId())
                    .knowledgeBaseId(knowledge.getId())
                    .status(failReason.isEmpty()?UploadStatus.SUCCESS: UploadStatus.FAILED)
                    .failReason(failReason).build();
            mapper.updateKnowledge(knowledgeBaseFile);
        }
//        mapper.BatchUpdate(knowledgeBaseFileList);
    }


}




