package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;

import java.util.List;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Service
* @createDate 2026-05-10 21:29:23
*
* <p>P2-7：删除了从未被调用、且引用了不存在列的 {@code searchMemory(SearchMemoryRequest)}
* （决策 D4 选 pg_trgm，不恢复 pgvector）。检索能力统一由 {@link #getMemory(String)} 提供。
*/
public interface UserMemoryService extends IService<UserMemory> {

    /**
     * 保存一条长期记忆。会先做归一化、长度截断与重复判定，重复则直接丢弃。
     * <p>
     * 注意是 {@code @Async} 的（写记忆不该拖慢对话），因此异常不会回传给调用方。
     */
    void saveMemory(UserMemory userMemory);

    /**
     * 检索长期记忆。
     *
     * @param key 检索串，允许 null / 空白（此时按最新返回若干条，供"浏览全部"用）
     * @return 命中的记忆，已排序（命中关键词多的优先、其次新的优先）、已去重、条数有上限
     */
    List<UserMemoryVO> getMemory(String key);

    /**
     * 删除指定记忆。**必须限定 user_id**（越权修复）：不是自己的记忆按"不存在"处理。
     */
    void deleteById(Long id);
}
