package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明: 用户文件
 */
@RestController
@RequestMapping("/file")
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }
    //    上传图片，比如头像~
    @PostMapping("/image")
    public ResponseEntity<String> uploadImage(MultipartFile file){
        String url= fileService.uploadImage(file);
        return ResponseEntity.ok(url);
    }
    @PostMapping
    public ResponseEntity<List<KnowledgeFileVO>> uploadFile(@RequestParam MultipartFile[] files, @RequestParam BizType bizType){
        List<KnowledgeFileVO> list = fileService.uploadFile(files,bizType);
        return ResponseEntity.ok(list);
    }

//    获取当前用户的文件列表
    @GetMapping
    public ResponseEntity<List<KnowledgeFileVO>> getUserFile(@RequestParam String fileName){
        List<KnowledgeFileVO> knowledgeFileVOS=fileService.getFileByUserId(fileName);
        return ResponseEntity.ok(knowledgeFileVOS);
    }
}
