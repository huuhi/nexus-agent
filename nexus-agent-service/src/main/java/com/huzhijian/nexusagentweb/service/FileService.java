package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.KnowledgeFile;
import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
* @author windows
* @description 针对表【file】的数据库操作Service
* @createDate 2026-04-16 20:02:47
*/
public interface FileService extends IService<KnowledgeFile> {

    List<String> uploadFile(MultipartFile[] file);

    String uploadImage(MultipartFile file);

    List<KnowledgeFileVO> getFileByUserId(String fileName);
}
