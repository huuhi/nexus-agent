package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;

import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base_file】的数据库操作Service
* @createDate 2026-04-16 20:02:41
*/
public interface KnowledgeBaseFileService extends IService<KnowledgeBaseFile> {
    /**
     * 把文件切分、向量化后写入向量库（异步）。
     * <p>
     * <b>向量模型口径</b>：固定使用系统默认向量模型（见实现类的说明），因此这里**不接收** configId/model。
     *
     * @param list        待入库的文件（调用方需保证属于同一用户）
     * @param userId      文件归属用户，写入向量 metadata 用于检索隔离
     * @param knowledgeId 目标知识库 ID
     */
    void embedding(List<SysFile> list, Long userId, int knowledgeId);

}
