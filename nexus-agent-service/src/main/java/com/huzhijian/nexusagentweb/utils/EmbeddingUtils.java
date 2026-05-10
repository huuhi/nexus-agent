package com.huzhijian.nexusagentweb.utils;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/10
 * 说明:
 */
@Component
public class EmbeddingUtils {
    private final EmbeddingModel embeddingModel;

    public EmbeddingUtils(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }
}
