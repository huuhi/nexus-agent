package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.NotSupportException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.service.FileService;
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
public class FileServiceImpl extends ServiceImpl<FileMapper, SysFile>
    implements FileService{

    private final AliOssUtil ossUtil;

    public FileServiceImpl(AliOssUtil ossUtil) {
        this.ossUtil = ossUtil;
    }

    @Override
    @Transactional
    public List<KnowledgeFileVO> uploadFile(MultipartFile[] files,BizType bizType) {
        if (files==null||files.length==0){
            throw new ValidationException("文件为空！");
        }
        List<SysFile> fileList =new ArrayList<>();
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
            } catch (ValidationException e) {
                // OSS 失败（凭证 / 网络 / 服务端拒绝）—— AliOssUtil 已转成带原因的业务异常
                failReason="上传失败！"+clip(e.getMessage());
            }catch (IOException e){
                failReason="读取文件失败！"+clip(e.getMessage());
            }
            SysFile knowledgeFile = SysFile.builder()
                    .fileSize(file.getSize())
                    .fileName(originalFilename)
                    .fileUrl(url)
                    .extension(fileExtension.toUpperCase())
                    .bizType(bizType)
                    .uploadStatus(failReason.isEmpty()? UploadStatus.SUCCESS: UploadStatus.FAILED)
                    .failReason(failReason)
                    .userId(userId)
                    .build();
            fileList.add(knowledgeFile);
        }
        saveBatch(fileList);
        return BeanUtil.copyToList(fileList, KnowledgeFileVO.class);

    }



    @Override
    public String uploadImage(MultipartFile file) {
        if (file==null||file.isEmpty()){
            throw new ValidationException("文件为空！");
        }
        String originalFilename = file.getOriginalFilename();
        String extension = FileTypeUtils.getFileExtension(originalFilename);
        if (FileTypeUtils.isSupportedImage(extension)) {
            try {
                return ossUtil.uploadImage(file.getBytes(), extension);
            } catch (IOException e) {
                throw new ValidationException("读取上传文件失败：" + e.getMessage());
            }
        }else{
            throw new NotSupportException("不支持的图片类型！");
        }
    }

    @Override
    public List<KnowledgeFileVO> getFileByUserId( String fileName, BizType bizType) {
        Long userId = UserContextHolder.getUserId();
        if (userId==null){
            throw new UnauthorizedException("用户未登录！");
        }
        List<SysFile> list = query().eq("user_id", userId)
                .eq(bizType!=null,"biz_type", bizType)
                .like(fileName != null, "file_name", fileName)
                .list();
        return BeanUtil.copyToList(list, KnowledgeFileVO.class);
    }

    /**
     * 按 id 批量取文件信息。
     * <p>
     * ⚠️ <b>2026-10-04 安全修复：补上 {@code user_id} 过滤。</b>
     * 原实现只有 {@code .in("id", fileIds)} —— 谁的 id 都查得到，
     * 拿到别人的文件 id 就能读出他的文件名与 OSS 地址（越权）。
     * <p>
     * 用户身份取 {@link UserContextHolder}（请求线程写入），
     * <b>不接受任何入参</b>，与 {@code getFileByUserId} 保持同一套约束。
     * <p>
     * 传别人的 id 时返回空列表（而不是报错）：调用方是详情页拼文件名，
     * 少一个附件不该让整页 500。
     */
    @Override
    public List<KnowledgeFileVO> queryFileByids(List<Long> fileIds) {
        if (fileIds==null|| fileIds.isEmpty()){
            return List.of();
        }
        Long userId = UserContextHolder.getUserId();
        if (userId == null){
            throw new UnauthorizedException("用户未登录！");
        }
        List<SysFile> sysFiles = query().in("id",fileIds)
                .eq("user_id", userId)
                .list();
        return BeanUtil.copyToList(sysFiles, KnowledgeFileVO.class);
    }

    /**
     * 删除当前用户自己的一条文件记录（不限 {@code biz_type}），并尽力删除 OSS 对象。
     * <p>
     * 见接口 {@link FileService#delete} 的说明：前端「文件与产物」统一视图需要一个
     * 对 CHAT / ARTIFACT / KNOWLEDGE 都生效的删除入口。
     */
    @Override
    @Transactional
    public boolean delete(Long id, Long userId) {
        if (id == null || userId == null) {
            return false;
        }
        // ⚠️ user_id 条件是越权防护的核心：id 来自客户端，不限定归属就能删别人的文件
        SysFile file = query().eq("id", id).eq("user_id", userId).one();
        if (file == null) {
            logDeleteMiss(id, userId);
            return false;
        }
        boolean removed = removeById(id);
        // 记录已删，对象残留只是存储成本 —— 失败只记 WARN，不能反过来让删除失败
        ossUtil.deleteByUrl(file.getFileUrl());
        log.info("已删除文件：id={} name={} bizType={}（记录删除={}）",
                id, file.getFileName(), file.getBizType(), removed);
        return removed;
    }

    /**
     * 删除落空时的诊断日志：与 {@code ArtifactServiceImpl#logDeleteMiss} 同一套口径
     * —— 对外不区分（防探测），但对内必须说清是「id 不存在」还是「越权」。
     */
    private void logDeleteMiss(Long id, Long userId) {
        SysFile anyOwner = getBaseMapper().selectById(id);
        if (anyOwner == null) {
            log.warn("删除文件落空：id={} 在 sys_file 中不存在（前端传的 id 可能不是 sys_file 主键，或该记录已删除）", id);
            return;
        }
        log.warn("删除文件落空：id={} 存在但不属于当前用户（记录归属 userId={}，本次请求 userId={}）",
                id, anyOwner.getUserId(), userId);
    }

    /**
     * 失败原因入库前的安全截断。
     * <p>
     * ⚠️ 原写法 {@code e.getMessage().substring(0,450)} 有两个坑：
     * <ol>
     *   <li>{@code getMessage()} 可能是 {@code null}（不少网络/超时异常没有消息）→ 直接 NPE，
     *       整批上传从"某个文件失败"升级成"整个接口 500"；</li>
     *   <li>消息不足 450 字符时 {@code substring(450)} 抛 StringIndexOutOfBoundsException。</li>
     * </ol>
     * 失败原因要写进 {@code file.fail_reason} 列（有长度约束），所以两头都得收口。
     */
    private static String clip(String message) {
        if (message == null || message.isBlank()) {
            return "未知原因";
        }
        String text = message.trim();
        return text.length() <= 450 ? text : text.substring(0, 450);
    }

}




