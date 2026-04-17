package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.KnowledgeFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseFileMapper;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.huzhijian.nexusagentweb.utils.FileTypeUtils.MS_OFFICE;

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
    private final AliOssUtil aliOssUtil;
    private final KnowledgeBaseFileMapper mapper;
    private final EmbeddingModel model;
    public KnowledgeBaseFileServiceImpl(PgVectorEmbeddingStore pgVectorEmbeddingStore, AliOssUtil aliOssUtil, KnowledgeBaseFileMapper mapper, EmbeddingModel model) {
        this.pgVectorEmbeddingStore = pgVectorEmbeddingStore;
        this.aliOssUtil = aliOssUtil;
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    @Async
    @Transactional
    public void embedding(List<KnowledgeFile> list, Long userId, KnowledgeBase knowledge) {
        DocumentSplitter splitter = DocumentSplitters.recursive(850, 150);
        for (KnowledgeFile file : list) {
            Path path =null;
            String failReason="";
            try {
                path  = Files.createTempFile("upload_", "-" + file.getFileName());
                String key = extractOssKeyFromUrl(file.getFileUrl());
                byte[] fileContent = aliOssUtil.downloadDocument(key);
                log.debug("key:{}",key);
                Files.write(path,fileContent);
                DocumentParser parser;
                if (file.getExtension().equals("pdf")){
                    parser=new ApachePdfBoxDocumentParser();
                }else if(MS_OFFICE.contains(file.getExtension())){
                    parser=new ApachePoiDocumentParser();
                }else{
                    parser=new ApacheTikaDocumentParser();
                }
                Document document = FileSystemDocumentLoader.loadDocument(path, parser);
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
            } finally {
                if (path!=null){
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignore) {}
                }
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
    private String extractOssKeyFromUrl(String ossUrl) {
        try {
            URL url = new URL(ossUrl);
            String path = url.getPath();
            // 移除开头的斜杠
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        } catch (MalformedURLException e) {
            // 如果不是有效的URL，假设它已经是对象键
            log.warn("OSS URL格式不正确，使用原始值: {}", ossUrl);
            return ossUrl;
        }
    }

}




