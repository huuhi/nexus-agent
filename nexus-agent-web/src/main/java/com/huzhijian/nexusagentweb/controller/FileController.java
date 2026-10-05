package com.huzhijian.nexusagentweb.controller;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
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

    /**
     * 删除自己的一个文件（2026-10-05 新增）。
     * <p>
     * 🔴 <b>这个端点就是为「文件与产物」统一视图准备的</b>：那份列表里同时有
     * 用户上传的对话附件（{@code biz_type=CHAT}）和 AI 产出物（{@code ARTIFACT}），
     * 删除按钮只有一个，而原先唯一的删除端点 {@code DELETE /api/artifact/{id}}
     * 硬限定 {@code biz_type=ARTIFACT} —— 于是对话附件怎么点都报
     * 「产物不存在或无权删除」。现在这个端点<b>不限类型</b>。
     * <p>
     * 删除动作 = 删数据库记录 + 尽力删 OSS 对象（先记录后对象，见
     * {@code FileService#delete}）。
     */
    @Operation(summary = "删除自己的一个文件（对话附件 / AI 产物均可）",
            description = """
                    「文件与产物」统一视图请用这个端点 —— 它**不限 biz_type**。
                    删除 = 数据库记录 + 尽力删 OSS 对象；记录不存在或不属于当前用户时
                    返回统一提示（后端刻意不区分，防止探测他人文件是否存在）。
                    """)
    @DeleteMapping("/{id}")
    public Result deleteFile(@PathVariable Long id) {
        boolean deleted = fileService.delete(id, currentUserId());
        return deleted ? Result.ok() : Result.error("文件不存在或无权限删除");
    }

    private Long currentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录!");
        }
        return userId;
    }
}
