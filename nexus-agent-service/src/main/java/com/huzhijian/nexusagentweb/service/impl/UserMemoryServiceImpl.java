package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.UserMemoryMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.utils.MemoryQueryParser;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Service实现
* @createDate 2026-05-10 21:29:23
*
* <p><b>P2-7 重写了检索与写入</b>（决策 D4 = pg_trgm，见
* {@code docs/sql/006_add_user_memory_trgm_index.sql}）。原实现只有一句
* {@code query().like(key != null, "content", key)}，有四个真问题：
* <ol>
*   <li><b>整串当一个关键词</b> —— 模型丢一句"用户喜欢吃什么口味的菜"进来必然零命中；</li>
*   <li><b>key 为 null 时返回全部</b> —— 会把用户所有记忆一次性塞进提示词；</li>
*   <li><b>无条数上限、无排序、无去重</b> —— 有多少返回多少，顺序随数据库心情；</li>
*   <li><b>不转义 LIKE 通配符</b> —— 关键词里带 {@code %} 会退化成"匹配全部"。</li>
* </ol>
* 现在：多关键词 OR 粗筛 → Java 按「命中关键词个数 + 新旧」排序 → 去重 → 截断条数；
* 字面匹配全落空时再用 pg_trgm 的 {@code similarity()} 兜底。
*
* <p><b>降级设计</b>：pg_trgm 是可选扩展。没装时 {@code similarity()} 会报错，
* 首次异常被捕获后永久关闭模糊检索（只 WARN 一次），字面匹配照常可用 —— 不会拖垮对话。
*
* <p><b>为什么用注入的 mapper 而不是 {@code ServiceImpl} 的 {@code baseMapper}</b>：
* 后者由框架按类型注入、单测里没法替换；显式用 {@code mapper} 才能用 mock 覆盖这些分支。
*/
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryServiceImpl extends ServiceImpl<UserMemoryMapper, UserMemory>
    implements UserMemoryService {

    private final UserMemoryMapper mapper;
    private final AgentProperties props;

    /** 数据库粗筛时多取几倍，留给 Java 排序（否则排序只在"前 N 条"内进行，等于没排） */
    private static final int OVER_FETCH = 4;

    /**
     * pg_trgm 是否不可用（没装扩展等）。一旦置位就不再重试，避免每轮对话都白打一次库。
     */
    private final AtomicBoolean fuzzyBroken = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    //  写入
    // ------------------------------------------------------------------

    @Async
    @Override
    public void saveMemory(UserMemory userMemory) {
        if (userMemory == null) {
            return;
        }
        String content = MemoryQueryParser.normalize(userMemory.getContent());
        if (content == null) {
            log.warn("长期记忆内容为空，忽略本次写入");
            return;
        }
        AgentProperties.Memory cfg = props.getMemory();
        int maxLen = Math.max(1, cfg.getMaxContentLength());
        if (content.length() > maxLen) {
            log.warn("长期记忆内容过长（{} 字），截断到 {} 字", content.length(), maxLen);
            content = content.substring(0, maxLen);
        }
        Long userId = userMemory.getUserId();
        if (userId != null && isDuplicate(userId, content)) {
            log.info("重复记忆，跳过写入：{}", content);
            return;
        }
        userMemory.setContent(content);
        mapper.insert(userMemory);
    }

    /**
     * 重复判定：先查归一化后完全一致的，再用 trigram 相似度查近似一致的。
     * 模型很容易在同一轮里反复保存同一条偏好，不去重会把库撑爆、把检索结果污染。
     */
    private boolean isDuplicate(Long userId, String content) {
        try {
            if (mapper.countByNormalizedContent(userId, content) > 0) {
                return true;
            }
        } catch (Exception e) {
            // 去重只是优化，失败时宁可写进去也不要丢内容
            log.warn("记忆去重查询失败，按不重复处理。原因：{}", e.getMessage());
            return false;
        }
        AgentProperties.Memory cfg = props.getMemory();
        if (!cfg.isFuzzy() || fuzzyBroken.get()) {
            return false;
        }
        try {
            return mapper.countSimilar(userId, content, cfg.getDedupThreshold()) > 0;
        } catch (Exception e) {
            markFuzzyBroken(e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  检索
    // ------------------------------------------------------------------

    @Override
    public List<UserMemoryVO> getMemory(String key) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            // 原来是 eq("user_id", null)，SQL 变成 user_id = NULL → 恒为空，静默返回"没记忆"
            throw new UnauthorizedException("用户未登录！");
        }
        AgentProperties.Memory cfg = props.getMemory();
        int limit = Math.max(1, cfg.getMaxResults());

        List<String> keywords = MemoryQueryParser.split(key, cfg.getMaxKeywords());
        List<UserMemory> rows;
        if (keywords.isEmpty()) {
            // 没给检索词：按最新返回若干条，而不是像原来那样返回全部
            rows = firstN(mapper.latestByUser(userId, limit), limit);
        } else {
            List<String> patterns = keywords.stream()
                    .map(MemoryQueryParser::likePattern)
                    .toList();
            rows = rank(mapper.searchByKeywords(userId, patterns, limit * OVER_FETCH), keywords, limit);
            if (rows.isEmpty()) {
                rows = fuzzySearch(userId, MemoryQueryParser.normalize(key), limit);
            }
        }
        return rows.stream().map(this::toVO).toList();
    }

    /**
     * 字面匹配全落空时的模糊兜底（pg_trgm）。
     * 能救回"喜欢看科幻电影" ↔ "喜欢看科幻片"这类<b>字面部分重叠</b>的表述；
     * 救不了"喜欢吃什么" ↔ "不吃辣"这种<b>语义相似</b>——那属于 pgvector 的活（D4 已推迟）。
     */
    private List<UserMemory> fuzzySearch(Long userId, String query, int limit) {
        AgentProperties.Memory cfg = props.getMemory();
        if (query == null || !cfg.isFuzzy() || fuzzyBroken.get()) {
            return List.of();
        }
        try {
            return firstN(mapper.searchBySimilarity(userId, query, cfg.getFuzzyMinScore(), limit), limit);
        } catch (Exception e) {
            markFuzzyBroken(e);
            return List.of();
        }
    }

    private void markFuzzyBroken(Exception e) {
        if (fuzzyBroken.compareAndSet(false, true)) {
            log.warn("pg_trgm 不可用，长期记忆已降级为纯字面匹配（ILIKE）。"
                            + "需要 fuzzy 检索请执行 docs/sql/006_add_user_memory_trgm_index.sql。原因：{}",
                    e.getMessage());
        }
    }

    /**
     * 排序 + 去重 + 截断。
     * <p>
     * 排序键：命中的关键词个数（多者优先）→ id（新的优先，id 自增可当时间序用）。
     * {@code create_at} 在库里是 {@code date}（只有日期），同日写入的排序不了，所以用 id。
     */
    private List<UserMemory> rank(List<UserMemory> candidates, List<String> keywords, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<UserMemory> sorted = candidates.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt((UserMemory m) -> -MemoryQueryParser.countMatches(m.getContent(), keywords))
                        .thenComparing(UserMemory::getId, Comparator.nullsFirst(Comparator.reverseOrder())))
                .toList();

        // 内容去重（键 = 去掉全部空白 + 转小写，与 SQL 侧 countByNormalizedContent 一致）：
        // 历史上同一条偏好被反复保存过，只留排序最靠前的那条
        Map<String, UserMemory> deduped = new LinkedHashMap<>();
        for (UserMemory m : sorted) {
            deduped.putIfAbsent(MemoryQueryParser.contentKey(m.getContent()), m);
        }
        return firstN(new ArrayList<>(deduped.values()), limit);
    }

    private List<UserMemory> firstN(List<UserMemory> rows, int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().limit(limit).toList();
    }

    private UserMemoryVO toVO(UserMemory m) {
        UserMemoryVO vo = new UserMemoryVO();
        vo.setId(m.getId());
        vo.setContent(m.getContent());
        vo.setSource(m.getSource());
        vo.setCreateAt(m.getCreateAt());
        return vo;
    }

    // ------------------------------------------------------------------
    //  删除
    // ------------------------------------------------------------------

    @Override
    public void deleteById(Long id) {
//        越权修复：必须限定 user_id，否则任何人可用别人的 id 删除其长期记忆
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录！");
        }
        int deleted = mapper.delete(Wrappers.<UserMemory>lambdaQuery()
                .eq(UserMemory::getId, id)
                .eq(UserMemory::getUserId, userId));
        if (deleted == 0) {
            throw new NotFoundException("记忆不存在或无权限操作");
        }
    }
}
