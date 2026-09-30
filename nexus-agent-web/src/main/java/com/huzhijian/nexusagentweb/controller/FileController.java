package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.service.FileService;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import com.huzhijian.nexusagentweb.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api/file")
@Tag(name = "文件", description = "上传（头像 / 知识库文件）与当前用户的文件列表")
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }
    //    上传图片，比如头像~
    @Operation(summary = "上传图片（头像等）")
    @PostMapping("/image")
    public Result uploadImage(MultipartFile file){
        String url= fileService.uploadImage(file);
        return Result.ok(url);
    }

    @Operation(summary = "批量上传文件（知识库入库用）",
            description = "`bizType` 决定用途（知识库 / 会话附件等），落库后按 `user_id` 归属。")
    @PostMapping
    public Result uploadFile(@RequestParam MultipartFile[] files, @RequestParam BizType bizType){
        List<KnowledgeFileVO> list = fileService.uploadFile(files,bizType);
        return Result.ok(list);
    }

//    获取当前用户的文件列表
    @Operation(summary = "当前用户的文件列表", description = "两个参数都可选，都留空 = 全部。")
    @GetMapping
    public Result getUserFile(@RequestParam(value = "fileName", required = false)String fileName,@RequestParam(value = "bizType", required = false)  BizType bizType){
        List<KnowledgeFileVO> knowledgeFileVOS=fileService.getFileByUserId(fileName,bizType);
        return Result.ok(knowledgeFileVOS);
    }
}
