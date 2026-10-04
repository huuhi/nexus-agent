package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.ChatHistoryListMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.vo.ChatSessionSearchVO;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Service实现
* @createDate 2026-04-18 11:52:04
*/
@Service
@Slf4j
public class ChatHistoryListServiceImpl extends ServiceImpl<ChatHistoryListMapper, ChatHistoryList>
    implements ChatHistoryListService{

    /** 搜索结果里的「命中位置」取值：标题命中 */
    public static final String MATCH_TITLE = "TITLE";
    /** 搜索结果里的「命中位置」取值：消息正文命中 */
    public static final String MATCH_CONTENT = "CONTENT";

    /**
     * 标题长度上限。数据库列是 {@code varchar(255)}，但标题是给人看的，
     * 超过 100 字符没有任何可读性 —— 在入口拦掉，而不是等数据库抛 {@code value too long}。
     * 与 {@code RenameSessionDTO} 的 {@code @Size} 保持一致。
     */
    private static final int MAX_TITLE_LENGTH = 100;

    /** 搜索关键词长度上限：再长就不是「搜索」而是把整段文章丢进来了 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final OpenAiChatModel model;
    private final ChatHistoryListMapper mapper;
    private final ChatMemoryService chatMemoryService;
    private final AgentProperties props;

    public ChatHistoryListServiceImpl(OpenAiChatModel model1, ChatHistoryListMapper mapper,
                                      ChatMemoryService chatMemoryService,
                                      AgentProperties props) {
        this.model = model1;
        this.mapper = mapper;
        this.chatMemoryService = chatMemoryService;
        this.props = props;
    }

    /**
     * 异步生成标题并入库。
     * <p>
     * ⚠️ <b>2026-10-04：不再向前端推送（WebSocket 已整体下线）。</b>
     * 原来生成完会 {@code sendToClient} 推一条 {@code {type:"title", data:...}}，
     * 但整个 WebSocket 只为这一个标题服务：为了一个非关键字段，
     * 要额外引入握手鉴权、来源限制、连接重连、单例约束、前后端两边的心跳处理 ——
     * 收益与成本完全不成比例。前端改为<b>下次拉会话列表时自然拿到新标题</b>
     * （{@code GET /api/history}），用户无感。
     * <p>
     * 标题是 {@code @Async} 生成的，返回时往往还没写完，
     * 所以<b>刚发完消息立刻拉列表可能拿到空标题</b>，这是既有行为、未改变。
     */
    @Override
    @Async
    public void createTitle(String sessionId, String message,String answer,Long userId) {
//        异步生成标题
        SystemMessage systemMessage = SystemMessage.from("""
            根据用户的问题及AI的回答生成标题。只返回标题内容，不允许返回其他任何无关内容，不允许自言自语，不要加"标题："等前缀。
                 问题：%s
                 回答：%s
            """.formatted(message, answer));
        String title = null;
        try {
            ChatResponse chat = model.chat(systemMessage);
            title = chat.aiMessage().text();
        } catch (Exception e) {
//          降级：出错就用用户的问题当标题。
//          ⚠️ 2026-10-05：原来这里既不打日志，又假设 message 非空 ——
//             message 为 null 时降级逻辑自己抛 NPE，标题彻底丢失，且 @Async 下异常无处可去。
            log.warn("标题生成失败，降级为用户问题：session={} 原因={}", sessionId, e.getMessage());
            String fallback = Objects.toString(message, "");
            int min = Math.min(255, fallback.length());
            title = fallback.substring(0, min).trim();
            if (title.isEmpty()) {
                title = "新对话";
            }
        }
        log.info("生成的标题：{}",title);
        ChatHistoryList history = ChatHistoryList.builder().sessionId(sessionId)
                .title(title)
                .userId(userId)
                .build();
        mapper.save(history);
    }

    @Override
    public void deleteSession(String sessionId) {
        Long userId = currentUserId();
        int remove= mapper.removeBySessionAndUserId(sessionId,userId);
        if (remove==1) {
            chatMemoryService.delByMemoryId(sessionId);
        }
    }

    @Override
    public List<ChatHistoryList> getList() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            return List.of();
        }
        return query().eq("user_id", userId).orderByDesc("update_time").list();

    }

    // ------------------------------------------------------------------
    //  重命名（P3-1 补）
    // ------------------------------------------------------------------

    @Override
    public void rename(String sessionId, String title) {
        Long userId = currentUserId();

        // sessionId 会被拼进 ::uuid，不是合法 UUID 时 PostgreSQL 直接抛异常变 500；
        // 这里提前拦掉，给前端一个可读的提示。
        if (!StringUtils.hasText(sessionId) || !isUuid(sessionId)) {
            throw new ValidationException("会话 ID 格式不正确");
        }
        String newTitle = title == null ? "" : title.trim();
        if (newTitle.isEmpty()) {
            throw new ValidationException("标题不能为空");
        }
        if (newTitle.length() > MAX_TITLE_LENGTH) {
            throw new ValidationException("标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }

        // SQL 里带 user_id：不带的話，改个 sessionId 就能改别人会话的标题
        int updated = mapper.updateTitleBySessionAndUserId(sessionId, newTitle, userId);
        if (updated == 0) {
            throw new NotFoundException("会话不存在或不属于当前用户");
        }
    }

    // ------------------------------------------------------------------
    //  搜索（P3-1 补）
    // ------------------------------------------------------------------

    @Override
    public List<ChatSessionSearchVO> search(String keyword) {
        Long userId = currentUserId();
        String kw = normalizeKeyword(keyword);
        String lower = kw.toLowerCase(Locale.ROOT);
        int maxSessions = props.getHistory().getSearchMaxSessions();

        // 用户自己的全部会话：标题命中要在这上面过滤，内容命中也要用它补标题。
        // 量级有限（会话列表接口本来就是全量返回），一次拉回来最省事。
        List<ChatHistoryList> mine = mapper.selectList(new LambdaQueryWrapper<ChatHistoryList>()
                .eq(ChatHistoryList::getUserId, userId)
                .orderByDesc(ChatHistoryList::getUpdateTime));

        List<ChatSessionSearchVO> result = new ArrayList<>();
        Set<String> matched = new HashSet<>();

        // ① 标题命中（忽略大小写）。标题可能为 null —— 异步生成失败时就是 null
        for (ChatHistoryList h : mine) {
            if (result.size() >= maxSessions) {
                break;
            }
            if (h.getTitle() != null && h.getTitle().toLowerCase(Locale.ROOT).contains(lower)) {
                matched.add(h.getSessionId());
                result.add(ChatSessionSearchVO.builder()
                        .sessionId(h.getSessionId())
                        .title(h.getTitle())
                        .updateTime(h.getUpdateTime())
                        .matchType(MATCH_TITLE)
                        .hitCount(0)
                        .build());
            }
        }

        // ② 内容命中。SQL 已按时间倒序返回，所以 LinkedHashMap 的插入顺序天然是「最近命中」优先
        List<ChatMemorySearchHit> hits = chatMemoryService.searchHits(
                userId, toLikePattern(kw), props.getHistory().getSearchMaxRows());

        Map<String, ChatMemorySearchHit> latestHit = new LinkedHashMap<>();
        Map<String, Integer> hitCounts = new HashMap<>();
        for (ChatMemorySearchHit hit : hits) {
            if (hit.getSessionId() == null) {
                continue;
            }
            latestHit.putIfAbsent(hit.getSessionId(), hit);
            hitCounts.merge(hit.getSessionId(), 1, Integer::sum);
        }

        Map<String, ChatHistoryList> bySessionId = new HashMap<>();
        for (ChatHistoryList h : mine) {
            if (h.getSessionId() != null) {
                bySessionId.put(h.getSessionId(), h);
            }
        }

        for (Map.Entry<String, ChatMemorySearchHit> e : latestHit.entrySet()) {
            if (result.size() >= maxSessions) {
                break;
            }
            String sid = e.getKey();
            if (matched.contains(sid)) {
                // 标题已命中就不再重复出现，保证一个会话只有一条结果
                continue;
            }
            ChatHistoryList meta = bySessionId.get(sid);
            ChatMemorySearchHit hit = e.getValue();
            result.add(ChatSessionSearchVO.builder()
                    .sessionId(sid)
                    .title(meta == null ? null : meta.getTitle())
                    // 会话可能还没来得及生成标题（chat_history_list 里没有这一行），
                    // 这时退回到命中消息的时间，保证前端有时间可展示
                    .updateTime(meta == null || meta.getUpdateTime() == null
                            ? hit.getCreateAt() : meta.getUpdateTime())
                    .matchType(MATCH_CONTENT)
                    .snippet(snippetAround(hit.getSnippet(), kw, props.getHistory().getSnippetRadius()))
                    .hitCount(hitCounts.get(sid))
                    .build());
        }
        return result;
    }

    /**
     * 把用户输入拼成 LIKE 模式：两侧包 {@code %}，中间的 {@code % _ \} 全部转义。
     * <p>
     * ⚠️ 转义**必须**与 SQL 里的 {@code escape '\'} 配对出现才有效：
     * PostgreSQL 的 LIKE 默认没有转义符，光写 {@code \%} 是「反斜杠 + 通配符」，
     * 用户搜一个 {@code %} 照样退化成匹配全部。
     */
    public static String toLikePattern(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length() + 2);
        sb.append('%');
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('%');
        return sb.toString();
    }

    /**
     * 截取关键词附近的一段文本作为片段，方便前端高亮。
     * 找不到关键词时（理论上不会发生，SQL 已过滤）退回开头一段。
     */
    public static String snippetAround(String text, String keyword, int radius) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        int idx = text.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            return text.substring(0, Math.min(text.length(), radius * 2));
        }
        int start = Math.max(0, idx - radius);
        int end = Math.min(text.length(), idx + keyword.length() + radius);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    private static String normalizeKeyword(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            throw new ValidationException("搜索关键词不能为空");
        }
        if (kw.length() > MAX_KEYWORD_LENGTH) {
            throw new ValidationException("搜索关键词不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        return kw;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Long currentUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        return userId;
    }
}
