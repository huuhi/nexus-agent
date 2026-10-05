package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.vo.BatchDeleteResultVO;
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

    /**
     * 批量删除当前用户名下的多条文件记录，并尽力删除对应的 OSS 对象（2026-10-05）。
     * <p>
     * <b>语义是「部分成功」而非全有全无</b>：逐条独立判断，一批里有的能删有的不能删时，
     * 能删的照样删，结果分两组返回。这样前端不必反复重试，也不必猜哪几条成功了。
     * <p>
     * <b>实现要点（都是踩过的坑）</b>：
     * <ol>
     *   <li><b>必须先按 {@code id + user_id} 一次性查出来</b>，不能直接
     *       {@code removeByIds(ids)} —— 那样等于把「删自己的文件」变成
     *       「只要知道 id 就能删任何人的文件」，IDOR。</li>
     *   <li><b>必须去重</b>：前端全选时很容易把同一批 id 重复传进来，
     *       而 {@code IN} 查询里重复值会让「删了几条」对不上。</li>
     *   <li><b>OSS 删除不参与成败判定</b>：对象残留只是存储成本，
     *       让它把整批操作判成失败，用户会以为文件还在。</li>
     * </ol>
     *
     * @param ids   待删除的 id 集合，允许含重复与null（内部清洗）
     * @param userId 当前用户 id
     * @return 逐条结果；ids 为空时返回各列表均为空、计数为 0 的结果对象（不返回 null）
     */
    BatchDeleteResultVO batchDelete(List<Long> ids, Long userId);
}
