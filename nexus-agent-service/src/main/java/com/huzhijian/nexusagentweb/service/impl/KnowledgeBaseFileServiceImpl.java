package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSONUtil;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.em.ModelType;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseFileMapper;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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
    private final UserConfigService userConfigService;


    @Override
    @Async
    @Transactional
    public void embedding(List<SysFile> list, Long userId, int knowledgeId, String configId, String model) {
        EmbeddingModel embeddingModel = null;
        String failReason="";
        try {
            embeddingModel = getEmbeddingModel(configId, model,userId);
        } catch (Exception e) {
            failReason = StringUtils.substring(e.getMessage(), 0, 250);
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(850, 150);
        for (SysFile file : list) {

            try {
                Objects.requireNonNull(embeddingModel, "embeddingModel 不能为空");
                Document document = fileUtils.getDocument(file);
                document.metadata().put("file_id",file.getId()).put("user_id",userId)
                        .put("file_name",file.getFileName()).put("knowledge",knowledgeId);
                List<TextSegment> textSegments = splitter.split(document);
                List<List<TextSegment>> batches = ListUtil.partition(textSegments, 10);
                for (List<TextSegment> batch : batches) {
                    List<Embedding>  content= embeddingModel.embedAll(batch).content();
                    pgVectorEmbeddingStore.addAll(content,textSegments);
                    Thread.sleep(200);
                }

            } catch (ClientException e) {
                failReason="参数错误！";
            } catch (InterruptedException e) {
                failReason="线程中断!";
                Thread.currentThread().interrupt();
            }catch (NullPointerException e){
//                不处理，因为failReason已经填了
            } catch (Exception e){
                log.error(e.getMessage());
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



    private EmbeddingModel getEmbeddingModel(String configId, String modelName,Long userId) {
        UserConfig userConfig = userConfigService.getUserConfig(userId);

        if (userConfig == null) {
            throw new NotFoundException("未设置配置");
        }
        String salt = userConfig.getSalt();
        String llmApiToken = userConfig.getLlmApiToken();
        List<APIConfig> configs = JSONUtil.toList(llmApiToken, APIConfig.class);
        APIConfig apiConfig = configs.stream().filter(config -> config.getId().equals(configId)).findFirst().orElse(null);
        if (apiConfig==null) {
            throw new NotFoundException("配置未发现");
        }
        boolean match = apiConfig.getModel().stream().anyMatch(model -> model.getType().equals(ModelType.EMBEDDING) && model.getName().equals(modelName));
        if (!match) {
            throw new NotFoundException("非向量模型/不存在的模型！");
        }
        String apiKey = EncryptorFactory.text(salt).decrypt(apiConfig.getAPIKey());
        //使用用户配置的向量模型
        System.out.println(apiKey);

        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .encodingFormat("float")
                .dimensions(1024)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .baseUrl(apiConfig.getBaseUrl())
                .build();
    }
}




