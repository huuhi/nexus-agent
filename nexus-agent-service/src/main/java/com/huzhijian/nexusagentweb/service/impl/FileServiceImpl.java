package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.KnowledgeFile;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import com.huzhijian.nexusagentweb.utils.FileTypeUtils;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
* @author windows
* @description 针对表【file】的数据库操作Service实现
* @createDate 2026-04-16 20:02:47
*/
@Service
@Slf4j
public class FileServiceImpl extends ServiceImpl<FileMapper, KnowledgeFile>
    implements FileService{

    private final AliOssUtil ossUtil;

    public FileServiceImpl(AliOssUtil ossUtil) {
        this.ossUtil = ossUtil;
    }

    @Override
    @Transactional
    public List<String> uploadFile(MultipartFile[] files) {
        if (files==null||files.length==0){
            throw new ValidationException("文件为空！");
        }
        List<String> urlList=new ArrayList<>();
        List<KnowledgeFile> fileList =new ArrayList<>();
        Long userId = UserContextHolder.getUserId();
        for (MultipartFile file : files) {
            if (file==null||file.isEmpty()) continue;
            String originalFilename = file.getOriginalFilename();
//        判断类型
            String fileExtension = FileTypeUtils.getFileExtension(originalFilename);
            if (!FileTypeUtils.isSupportedDocument(fileExtension)) {
                continue;
            }
            String url = "";
            String failReason="";
            try {
                url= ossUtil.uploadDocument(file.getBytes(), fileExtension,userId);
                log.info("添加成功，url:{}",url);
                urlList.add(url);
            } catch (ClientException e) {
                failReason="配置错误！"+e.getMessage().substring(0,450);
            }catch (IOException e){
                failReason="IO异常！"+e.getMessage().substring(0,450);
            }
            KnowledgeFile knowledgeFile = KnowledgeFile.builder()
                    .fileSize(file.getSize())
                    .fileName(originalFilename)
                    .fileUrl(url)
                    .extension(fileExtension)
                    .uploadStatus(failReason.isEmpty()? UploadStatus.SUCCESS: UploadStatus.FAILED)
                    .failReason(failReason)
                    .userId(userId)
                    .build();
            fileList.add(knowledgeFile);
        }
        saveBatch(fileList);
        return urlList;
    }



    @Override
    public String uploadImage(MultipartFile file) {
        if (file==null||file.isEmpty()){
            throw new ValidationException("文件为空！");
        }
        String originalFilename = file.getOriginalFilename();
        if (FileTypeUtils.isSupportedImage(originalFilename)){
            try {
                return ossUtil.uploadImage(file.getBytes(), originalFilename);
            } catch (ClientException | IOException e) {
                throw new ValidationException(e.getMessage());
            }
        }
        return null;
    }

    @Override
    public List<KnowledgeFileVO> getFileByUserId(String fileName) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            return List.of();
        }
        List<KnowledgeFile> list = query().eq("user_id", userId)
                .like(fileName != null, "file_name", fileName)
                .list();
        return BeanUtil.copyToList(list, KnowledgeFileVO.class);
    }

}




