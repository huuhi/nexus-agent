package com.huzhijian.nexusagentweb.mapper;

import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base_file】的数据库操作Mapper
* @createDate 2026-04-16 20:02:41
* @Entity com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile
*/
public interface KnowledgeBaseFileMapper extends BaseMapper<KnowledgeBaseFile> {


    void updateKnowledge(KnowledgeBaseFile knowledgeBaseFile);
}




