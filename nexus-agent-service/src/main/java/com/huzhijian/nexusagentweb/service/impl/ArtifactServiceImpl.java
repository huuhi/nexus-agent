package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.service.ArtifactService;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/9/24
 * 说明: 产物落库的默认实现（P2-10）。
 * <p>
 * 只做"记录元数据"这一件事：文件本体已经在 OSS 上了（沙盒服务上传时返回 URL），
 * 这里把 URL、文件名、大小、扩展名、归属用户与会话写进 {@code sys_file}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactServiceImpl implements ArtifactService {

    private final FileMapper fileMapper;
    private final AliOssUtil aliOssUtil;

    @Override
    public List<SysFile> listBySession(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return List.of();
        }
        return fileMapper.selectList(Wrappers.<SysFile>lambdaQuery()
                // ⚠️ user_id 条件是越权防护的核心，不能删（sessionId 来自客户端）
                .eq(SysFile::getUserId, userId)
                .eq(SysFile::getSessionId, sessionId)
                .eq(SysFile::getBizType, BizType.ARTIFACT)
                .orderByDesc(SysFile::getCreateTime));
    }

    @Override
    public boolean delete(Long id, Long userId) {
        if (id == null || userId == null) {
            return false;
        }
        // 先按 id + user_id 查：既校验归属，又拿到 URL 用于删 OSS 对象
        SysFile file = fileMapper.selectOne(Wrappers.<SysFile>lambdaQuery()
                .eq(SysFile::getId, id)
                .eq(SysFile::getUserId, userId)
                .eq(SysFile::getBizType, BizType.ARTIFACT));
        if (file == null) {
            // 不存在 / 不属于该用户：统一返回 false，不通过返回值泄露他人产物的存在性
            return false;
        }
        fileMapper.deleteById(id);
        deleteOssObject(file.getFileUrl());
        return true;
    }

    /**
     * 尽力删除 OSS 对象。
     * <p>
     * 失败只记 WARN：记录已经删了，对象残留只是存储成本；报错反而会让用户以为没删掉。
     */
    private void deleteOssObject(String fileUrl) {
        String objectName = AliOssUtil.objectNameOf(fileUrl);
        if (objectName == null) {
            log.warn("无法从 URL 解析出 OSS 对象名，跳过对象删除：url={}", fileUrl);
            return;
        }
        try {
            aliOssUtil.deleteObject(objectName);
        } catch (Exception e) {
            log.warn("删除 OSS 对象失败（已忽略，数据库记录已删除）：object={} 原因={}",
                    objectName, e.getMessage());
        }
    }

    @Override
    public SysFile save(Map<String, Object> artifact, Long userId, String sessionId) {
        if (artifact == null || artifact.isEmpty()) {
            return null;
        }
        if (userId == null) {
            // sys_file.user_id 是 NOT NULL，拿不到 userId 就没法落库。
            // 不抛异常：产物文件其实已经生成好了，不落库只是少一条元数据记录。
            log.warn("产物落库跳过：拿不到 userId（session={}）。文件本身已上传 OSS，只是没有元数据记录",
                    sessionId);
            return null;
        }
        SysFile file = SysFile.builder()
                .userId(userId)
                .sessionId(sessionId)
                .bizType(BizType.ARTIFACT)
                // 能走到这里说明沙盒服务已成功上传 OSS 且返回了非空 URL（BoxTool 已校验）
                .uploadStatus(UploadStatus.SUCCESS)
                .fileUrl(asString(artifact.get("url")))
                .fileName(asString(artifact.get("name")))
                .extension(asString(artifact.get("extension")))
                .fileSize(asLong(artifact.get("size")))
                .createTime(new Date())
                .build();
        fileMapper.insert(file);
        log.debug("产物已落库：id={} name={} size={} session={}",
                file.getId(), file.getFileName(), file.getFileSize(), sessionId);
        return file;
    }

    /**
     * 安全转字符串：artifact 里的字段来自工具结果（JSON），类型不保证一致
     * （例如 size 可能是 Integer 也可能是 String），故统一做一次兜底转换。
     */
    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            // 拿不到大小不影响产物可用性，只是列表里少显示一个体积
            return null;
        }
    }
}
