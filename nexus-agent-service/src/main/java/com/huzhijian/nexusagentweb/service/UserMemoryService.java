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
     * ⚠️ <b>2026-10-05：去掉了 {@code @Async}</b>。原因：异步执行时异常不会回传给调用方，
     * 于是「写库失败」在工具侧表现为**返回 ok 但数据库里什么都没有** —— 用户查库查不到、
     * 日志里也没有线索（这是线上真实发生过的故障）。保存只是一两条 INSERT/SELECT，
     * 同步执行的耗时可以忽略，换来的却是"失败能被看见"。
     *
     * @throws com.huzhijian.nexusagentweb.exception.UnauthorizedException userId 为 null（工具线程取不到登录态时的典型症状）
     */
    void saveMemory(UserMemory userMemory);

    /**
     * 检索长期记忆（请求线程用，从 {@link com.huzhijian.nexusagentweb.context.UserContextHolder} 取用户）。
     *
     * @param key 检索串，允许 null / 空白（此时按最新返回若干条，供"浏览全部"用）
     * @return 命中的记忆，已排序（命中关键词多的优先、其次新的优先）、已去重、条数有上限
     */
    List<UserMemoryVO> getMemory(String key);

    /**
     * 检索长期记忆（**显式指定用户**）。
     * <p>
     * 🔴 <b>工具里必须调这个重载</b>：工具跑在 LangChain4j 的流式回调线程上，
     * 那里 {@code UserContextHolder}（ThreadLocal）必然为 null，
     * 走 {@link #getMemory(String)} 只会抛「用户未登录」—— 表现为"模型永远检索不到记忆"。
     * 工具侧应用 {@code RunUserRegistry} + {@code @ToolMemoryId} 反查出 userId 再传进来。
     *
     * @param userId 用户 id，<b>不允许为 null</b>
     * @param key    检索串，同 {@link #getMemory(String)}
     */
    List<UserMemoryVO> getMemory(Long userId, String key);

    /**
     * 删除指定记忆。**必须限定 user_id**（越权修复）：不是自己的记忆按"不存在"处理。
     */
    void deleteById(Long id);
}
