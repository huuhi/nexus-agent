package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.KnowledgeFile;

import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base_file】的数据库操作Service
* @createDate 2026-04-16 20:02:41
*/
public interface KnowledgeBaseFileService extends IService<KnowledgeBaseFile> {
    void embedding(List<KnowledgeFile> list, Long userId, KnowledgeBase knowledgeBase);

}
