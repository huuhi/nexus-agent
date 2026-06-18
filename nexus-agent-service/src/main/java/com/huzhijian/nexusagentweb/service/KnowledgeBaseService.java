package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.dto.KnowledgeFileDTO;
import com.huzhijian.nexusagentweb.vo.KnowledgeDetailVO;

import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base】的数据库操作Service
* @createDate 2026-04-16 20:02:44
*/
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    String insertKnowledge(KnowledgeFileDTO knowledgeDTO);

    void createKnowledge(KnowledgeDTO knowledgeDTO);

    List<KnowledgeBase> getKnowledgeList();

    KnowledgeDetailVO getKnowledgeById(Integer id);
}
