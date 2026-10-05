package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.SysFile;
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

    List<KnowledgeFileVO> getFileByUserId(String fileName,BizType bizType);

    List<KnowledgeFileVO> queryFileByids(List<Long> fileIds);

    /**
     * 删除<b>当前用户自己</b>的一条文件记录，并尽力删除对应的 OSS 对象（2026-10-05 新增）。
     * <p>
     * 🔴 <b>为什么要有这个通用入口</b>：前端「文件与产物」是<b>统一视图</b>
     * （一份列表里同时有用户上传的对话附件 {@code biz_type=CHAT} 和 AI 产物 {@code ARTIFACT}），
     * 删除按钮只有一个。而 {@code DELETE /api/artifact/{id}} 原先硬限定 {@code biz_type=ARTIFACT}，
     * 于是 13 个对话附件怎么点都返回「产物不存在或无权删除」。
     * <p>
     * <b>删除顺序</b>：先删数据库记录，再尽力删 OSS 对象。用户点删除的语义以记录为准，
     * 对象残留只是存储成本；反过来一旦记录删除失败，用户会看到一个点开就 404 的条目。
     * <p>
     * <b>越权防护</b>：只认 {@code id + user_id}，两者都命中才删。
     *
     * @return true = 确实删掉了一条；false = 不存在 / 不属于该用户（<b>两种情况不区分</b>，
     *         避免通过返回值探测他人文件是否存在）
     */
    boolean delete(Long id, Long userId);
}
