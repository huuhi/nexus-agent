package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseFileMapper;
import com.huzhijian.nexusagentweb.service.impl.KnowledgeBaseFileServiceImpl;
import com.huzhijian.nexusagentweb.utils.FileUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseFileServiceImpl 的纯单元测试（P2-13）。
 * <p>
 * 覆盖两件事：
 * <ol>
 *   <li>入库**使用注入的系统向量模型**，而不是去用户 API 配置里找模型
 *      （旧实现会导致：没配 API Key 的用户建不了库；即便配上，入库/检索模型不同 →
 *       向量空间不可比 → 检索结果完全不相关且不报错）；</li>
 *   <li>失败原因按**文件**隔离（旧实现 failReason 定义在循环外，一个文件失败会污染后面全部文件）。</li>
 * </ol>
 * 不依赖 Spring、不访问网络、不连数据库。
 */
class KnowledgeBaseFileServiceImplTest {

    private final PgVectorEmbeddingStore store = mock(PgVectorEmbeddingStore.class);
    private final FileUtils fileUtils = mock(FileUtils.class);
    private final KnowledgeBaseFileMapper mapper = mock(KnowledgeBaseFileMapper.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

    private final KnowledgeBaseFileServiceImpl service =
            new KnowledgeBaseFileServiceImpl(store, fileUtils, mapper, embeddingModel);

    private static SysFile file(long id, String name) {
        return SysFile.builder().id(id).fileName(name).fileUrl("https://oss/" + name).build();
    }

    private void stubOkDocument(String text) throws Exception {
        when(fileUtils.getDocument(any())).thenReturn(Document.from(text));
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[]{0.1f, 0.2f}))));
    }

    @Test
    @DisplayName("入库用注入的系统向量模型，与检索同一个（不读用户 API 配置）")
    void usesSystemEmbeddingModel() throws Exception {
        stubOkDocument("这是一段用于入库的测试文本。");

        service.embedding(List.of(file(1L, "a.md")), 42L, 7);

        verify(embeddingModel).embedAll(anyList());
        verify(store).addAll(anyList(), anyList());
    }

    @Test
    @DisplayName("向量与文本按批次一一对应（不能把全量 segments 传给 addAll）")
    void addAllReceivesTheBatchThatWasEmbedded() throws Exception {
        stubOkDocument("短文本");

        service.embedding(List.of(file(1L, "a.md")), 42L, 7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(anyList(), segments.capture());
        assertTrue(!segments.getValue().isEmpty(), "addAll 必须带上被向量化的那批文本");
    }

    @Test
    @DisplayName("成功入库的文件标记为 SUCCESS 且无失败原因")
    void successMarkedWithoutFailReason() throws Exception {
        stubOkDocument("内容");

        service.embedding(List.of(file(1L, "a.md")), 42L, 7);

        ArgumentCaptor<KnowledgeBaseFile> captor = ArgumentCaptor.forClass(KnowledgeBaseFile.class);
        verify(mapper).updateKnowledge(captor.capture());
        assertEquals(UploadStatus.SUCCESS, captor.getValue().getStatus());
        assertEquals("", captor.getValue().getFailReason());
    }

    @Test
    @DisplayName("失败原因按文件隔离：前一个文件失败不会污染后一个文件")
    void failReasonIsPerFile() throws Exception {
        when(fileUtils.getDocument(any()))
                .thenThrow(new RuntimeException("磁盘读取失败"))
                .thenReturn(Document.from("正常内容"));
        when(embeddingModel.embedAll(anyList()))
                .thenReturn(Response.from(List.of(new Embedding(new float[]{0.1f}))));

        service.embedding(List.of(file(1L, "bad.md"), file(2L, "good.md")), 42L, 7);

        ArgumentCaptor<KnowledgeBaseFile> captor = ArgumentCaptor.forClass(KnowledgeBaseFile.class);
        verify(mapper, org.mockito.Mockito.times(2)).updateKnowledge(captor.capture());

        List<KnowledgeBaseFile> updates = captor.getAllValues();
        assertEquals(UploadStatus.FAILED, updates.get(0).getStatus());
        assertTrue(String.valueOf(updates.get(0).getFailReason()).contains("磁盘读取失败"));

        // 旧实现这里会被上一个文件的 failReason 污染，变成 FAILED
        assertEquals(UploadStatus.SUCCESS, updates.get(1).getStatus());
        assertEquals("", updates.get(1).getFailReason());
    }
}
