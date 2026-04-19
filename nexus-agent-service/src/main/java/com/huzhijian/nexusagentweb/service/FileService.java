package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.SysFile;
import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
* @author windows
* @description 针对表【file】的数据库操作Service
* @createDate 2026-04-16 20:02:47
*/
public interface FileService extends IService<SysFile> {

    List<KnowledgeFileVO> uploadFile(MultipartFile[] file, BizType bizType);

    String uploadImage(MultipartFile file);

    List<KnowledgeFileVO> getFileByUserId(String fileName);
}
