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
        // 先按 id + user_id 查：既校验归属，又拿到 URL 用于删 OSS 对象。
        //
        // 🔴 2026-10-05：这里原来还带了 `.eq(SysFile::getBizType, BizType.ARTIFACT)`，
        // 而前端「文件与产物」是一个**统一视图**（42 个文件 = 13 个对话附件 + 29 个产物），
        // 删除按钮对所有行都调这个端点 —— 于是 biz_type=CHAT（用户上传的附件）
        // 永远命中不了，用户看到的就是「产物不存在或无权删除」。
        // 归属校验本来就靠 user_id，bizType 不是安全边界，加它只会误伤，已移除。
        SysFile file = fileMapper.selectOne(Wrappers.<SysFile>lambdaQuery()
                .eq(SysFile::getId, id)
                .eq(SysFile::getUserId, userId));
        if (file == null) {
            // 不存在 / 不属于该用户：统一返回 false，不通过返回值泄露他人产物的存在性
            logDeleteMiss(id, userId);
            return false;
        }
        fileMapper.deleteById(id);
        aliOssUtil.deleteByUrl(file.getFileUrl());
        return true;
    }

    /**
     * 删除落空时的诊断日志。
     * <p>
     * 🔴 <b>"不存在"和"不属于你"在返回值上必须一视同仁</b>（否则就成了探测他人文件的探针），
     * 但线上排查时这两者天差地别，所以只在日志里区分清楚：
     * <ul>
     *   <li>{@code sys_file} 里根本没有这个 id → 前端传的 id 不对（注意：可能是 JS 精度问题，
     *       {@code sys_file.id} 是雪花算法生成的 Long，若哪天长度超过 2^53，
     *       JSON 数字在浏览器里会被四舍五入）；</li>
     *   <li>id 存在但属于别的用户 → 越权访问尝试，是需要关注的安全信号。</li>
     * </ul>
     */
    private void logDeleteMiss(Long id, Long userId) {
        SysFile anyOwner = fileMapper.selectById(id);
        if (anyOwner == null) {
            log.warn("删除文件落空：id={} 在 sys_file 中不存在（前端传的 id 可能不是 sys_file 主键，或该记录已删除）", id);
            return;
        }
        log.warn("删除文件落空：id={} 存在但不属于当前用户（记录归属 userId={}，本次请求 userId={}）",
                id, anyOwner.getUserId(), userId);
    }

    @Override
    public SysFile save(Map<String, Object> artifact, Long userId, String sessionId, String runId) {
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
                // 产物归属（方案 B）：与 chat_memory.run_id 配套，前端按字符串相等匹配
                .runId(runId)
                .createTime(new Date())
                .build();
        fileMapper.insert(file);
        log.debug("产物已落库：id={} name={} size={} session={} runId={}",
                file.getId(), file.getFileName(), file.getFileSize(), sessionId, runId);
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
