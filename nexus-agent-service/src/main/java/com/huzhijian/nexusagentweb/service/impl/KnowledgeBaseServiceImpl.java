package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.KnowledgeFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
* @author windows
* @description 针对表【knowledge_base】的数据库操作Service实现
* @createDate 2026-04-16 20:02:44
*/
@Service
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
    implements KnowledgeBaseService{

    private final FileService fileService;
    private final KnowledgeBaseFileService knowledgeBaseFileService;

    public KnowledgeBaseServiceImpl(FileService fileService, KnowledgeBaseFileService knowledgeBaseFileService) {
        this.fileService = fileService;

        this.knowledgeBaseFileService = knowledgeBaseFileService;
    }


    @Override
    public String insertKnowledge(List<Long> fileIds,Integer knowledgeId) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
//            抛出401
            throw new UnauthorizedException("未登录！");
        }
//        这里拿到文件之后，异步处理
        List<KnowledgeFile> list = fileService.query().in("id", fileIds)
                .eq("user_id", userId)
                .list();
        KnowledgeBase knowledgeBase = query().eq("id", knowledgeId).one();
        if (list.isEmpty()) return "文件错误！";
        if (knowledgeBase==null) return "知识库不存在！";
//        先保存
        List<KnowledgeBaseFile> knowledgeBaseFileList=new ArrayList<>();
        for (KnowledgeFile knowledgeFile : list) {
            KnowledgeBaseFile knowledgeBaseFile = KnowledgeBaseFile.builder().status(UploadStatus.PROCESSING)
                    .fileId(knowledgeFile.getId())
                    .knowledgeBaseId(knowledgeId)
                    .build();
            knowledgeBaseFileList.add(knowledgeBaseFile);
        }
        knowledgeBaseFileService.saveBatch(knowledgeBaseFileList);
        knowledgeBaseFileService.embedding(list,userId,knowledgeBase);
        return "上传中~";
    }
}




