package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.KnowledgeBase;
import com.huzhijian.nexusagentweb.domain.KnowledgeBaseFile;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.dto.KnowledgeDTO;
import com.huzhijian.nexusagentweb.dto.KnowledgeFileDTO;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.KnowledgeBaseMapper;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseFileService;
import com.huzhijian.nexusagentweb.service.KnowledgeBaseService;
import com.huzhijian.nexusagentweb.vo.KnowledgeDetailVO;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
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
    public String insertKnowledge(KnowledgeFileDTO knowledgeDTO) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
//            抛出401
            throw new UnauthorizedException("未登录！");
        }
        Integer knowledgeId = knowledgeDTO.knowledgeId();
        List<Long> fileIds = knowledgeDTO.fileIds();
//        这里拿到文件之后，异步处理
        List<SysFile> list = fileService.query().in("id", fileIds)
                .eq("user_id", userId)
                .list();
        KnowledgeBase knowledgeBase = query().eq("id", knowledgeId).one();
        if (list.isEmpty()) return "文件错误！";
        if (knowledgeBase==null) return "知识库不存在！";
//        先保存
        List<KnowledgeBaseFile> knowledgeBaseFileList=new ArrayList<>();
        for (SysFile knowledgeFile : list) {
            KnowledgeBaseFile knowledgeBaseFile = KnowledgeBaseFile.builder().status(UploadStatus.PROCESSING)
                    .fileName(knowledgeFile.getFileName())
                    .fileId(knowledgeFile.getId())
                    .knowledgeBaseId(knowledgeId)
                    .build();
            knowledgeBaseFileList.add(knowledgeBaseFile);
        }
        knowledgeBaseFileService.saveBatch(knowledgeBaseFileList);
        knowledgeBaseFileService.embedding(list,userId,knowledgeId,knowledgeDTO.configId(),knowledgeDTO.model());
        return "上传中~";
    }

    @Override
    public void createKnowledge(KnowledgeDTO dto) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("未登录！");
        }
        KnowledgeBase build = KnowledgeBase.builder()
                .name(dto.name())
                .describe(dto.describe())
                .isPublic(dto.isPublic())
                .userId(userId)
                .languageCode(dto.languageCode())
                .build();
        save(build);
    }

    @Override
    public List<KnowledgeBase> getKnowledgeList() {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("未登录！");
        }
        return query().eq("user_id", userId).list();
    }

    @Override
    public KnowledgeDetailVO getKnowledgeById(Integer id) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("未登录！");
        }
        KnowledgeBase knowledgeBase = getById(id);
        if (knowledgeBase==null){
            throw new NotFoundException("知识库不存在！");
        };
        List<KnowledgeBaseFile> fileBaseList = knowledgeBaseFileService.query().eq("knowledge_base_id", id).list();
        List<Long> fileIds = fileBaseList.stream().map(KnowledgeBaseFile::getFileId).toList();
        List<KnowledgeFileVO> files= fileService.queryFileByids(fileIds);

        KnowledgeDetailVO detailVO = BeanUtil.copyProperties(knowledgeBase, KnowledgeDetailVO.class);
        detailVO.setKnowledgeBaseFileList(files);
        return detailVO;
    }
}




