package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.ChatMemorySearchHit;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.ChatMemoryMapper;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.tools.ToolVisibility;
import com.huzhijian.nexusagentweb.vo.AttachedFileVO;
import com.huzhijian.nexusagentweb.vo.MessageVO;
import dev.langchain4j.data.message.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.huzhijian.nexusagentweb.content.MetadataKeyContent.*;

/**
* @author windows
* @description 针对表【chat_memory(用户表)】的数据库操作Service实现
* @createDate 2026-04-16 20:02:49
*/
@Service
@Slf4j
public class ChatMemoryServiceImpl extends ServiceImpl<ChatMemoryMapper, ChatHistory>
    implements ChatMemoryService{

    @Resource
    private ChatMemoryMapper mapper;
    /** 2026-10-07：工具调用的展示层可见性（决定历史里要不要出现某条工具消息） */
    @Resource
    private ToolVisibility toolVisibility;
    private static final ObjectMapper MAPPER = new ObjectMapper();
//    文件
    private final Pattern FILE_PATTERN = Pattern.compile(
            "<<FILE_START id=\"(.*?)\">>.*?<<FILE_END>>",
            Pattern.DOTALL
    );
//    图片
    private final Pattern IMAGE_PATTERN = Pattern.compile(
            "<<IMAGE_START id=\"(.*?)\">>.*?<<IMAGE_END>>",
            Pattern.DOTALL
    );

    /**
     * 兼容**存量数据**的包裹标记：无论新旧格式（两括号带 id / 三括号无 id），
     * 只要是配对的 FILE/IMAGE 包裹块就从文本里剥掉。
     * <p>
     * 背景：图片消息曾用「URL 包在 TextContent 里」的方案，标记就被存进了 chat_memory；
     * 2026-10-03 改用真正的 {@code ImageContent} 后，新消息不再产生这些文本，
     * 但历史库里已经存了的还在 —— 读取时在这里统一剥掉，前端才不会显示出一坨标记。
     */
    private static final Pattern LEGACY_WRAPPER = Pattern.compile(
            "<<{2,3}(IMAGE|FILE)_START( id=\"[^\"]*\")?>>{2,3}.*?<<{2,3}\\1_END>>{2,3}",
            Pattern.DOTALL
    );

    /** 剥掉文本里残留的文件/图片包裹标记（存量数据兼容），并收敛首尾空白 */
    private static String stripLegacyWrappers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return LEGACY_WRAPPER.matcher(text).replaceAll("").strip();
    }

    // ======================================================================
    //  🔴 superseded_by 列的降级闸门（2026-10-06）
    //
    //  背景：本类新增了 chat_memory.superseded_by（docs/sql/013）的读写。
    //  而「历史接口」与「记忆加载」是本项目最核心的两条链路，它们都直接 SELECT 这一列 ——
    //  一旦迁移脚本没执行，PostgreSQL 抛 `column "superseded_by" does not exist`，
    //  于是【发消息】与【拉历史】一起 500，首页白屏。
    //
    //  为什么必须降级而不是直接报错：
    //  · 项目惯例一直是「DB 查询失败 → 降级放行 + 明确日志」（见 QuotaServiceImpl 的两处）；
    //  · SchemaStartupChecker 虽然会在启动期报缺列，但配置了 startup.fail-fast=false 时
    //    应用会带着缺列启动 —— 那时更需要运行期能活下来，而不是全线 500；
    //  · 降级后的表现是「版本切换功能不可用，但聊天完全正常」，
    //    这比「整个用不了」好一个数量级，用户还能正常对话。
    //
    //  只探测一次（volatile 布尔）：缺列是部署级的既定状态，不会跑着跑着就好了，
    //  每次都 try-catch 纯属浪费。
    // ======================================================================
    /** superseded_by 列是否可用；null = 还没探测过 */
    private volatile Boolean supersededByAvailable;

    /**
     * 读历史（全量，含被替代的版本 —— 前端要靠它们做 n/n 切换）。
     * <p>
     * ⚠️ 与 {@link #getActiveForChat} 刻意不同：那个是给模型上下文用的，要排除被替代的。
     */
    private List<ChatHistory> queryAllForHistory(Object sessionId, Long userId) {
        try {
            return mapper.getAllByMemoryIdAndUserId(sessionId, userId);
        } catch (Exception e) {
            if (markSupersededUnsupported(e)) {
                return queryAllWithoutSuperseded(sessionId, userId);
            }
            throw e;
        }
    }

    /**
     * 读「当前生效」的消息（供记忆加载）。
     * <p>
     * 缺列时退化为 {@code getRecentForChat}（**不**排除被替代的版本）——
     * 功能降级为「版本切换不生效」，但对话正常。
     */
    private List<ChatHistory> queryActiveForMemory(Object sessionId, Long userId, int limit) {
        try {
            return mapper.getActiveForChat(sessionId, userId, limit <= 0 ? Integer.MAX_VALUE : limit);
        } catch (Exception e) {
            if (markSupersededUnsupported(e)) {
                return mapper.getRecentForChat(sessionId, userId, limit);
            }
            throw e;
        }
    }

    /**
     * 缺列时用的降级查询：SELECT 不带 {@code superseded_by}。
     * <p>
     * 它与 {@code getAllByMemoryIdAndUserId} 的唯一差别就是那一列 ——
     * 必须单独写一条 SQL，不能靠「把列名参数化」（列名不能是绑定参数）。
     */
    private List<ChatHistory> queryAllWithoutSuperseded(Object sessionId, Long userId) {
        return mapper.getAllByMemoryIdAndUserIdWithoutSuperseded(sessionId, userId);
    }

    /**
     * 判断这次异常是不是「superseded_by 列不存在」，并记一次降级日志。
     * <p>
     * 🔴 <b>必须遍历 cause 链</b>，只看最外层 {@code getMessage()} 是<b>错的</b>：
     * MyBatis 抛的是 {@code BadSqlGrammarException}，它的 message 形如
     * {@code "query; bad SQL grammar [select ... ]"} —— <b>里面没有任何列名</b>，
     * 真正的 {@code ERROR: column "superseded_by" does not exist} 藏在 cause 里。
     * 只看外层的话降级永远不会触发，缺列时照样全线 500（这个坑是
     * {@code SupersededByFallbackTest} 抓出来的，不是想出来的）。
     *
     * @return true = 确认为缺列，调用方应走降级查询
     */
    private boolean markSupersededUnsupported(Throwable e) {
        if (!isMissingSupersededColumn(e)) {
            return false;
        }
        if (supersededByAvailable == null) {
            supersededByAvailable = Boolean.FALSE;
            log.error("""
                    🔴 降级：chat_memory.superseded_by 列不存在，「重新生成的版本切换」不可用。
                       聊天与历史**完全正常**（只是同一问题的多个回答会同时进模型上下文）。
                       请执行：docs/sql/013_add_superseded_by.sql
                       （进程内只探测这一次；补完列后需重启才恢复）""");
        }
        return true;
    }

    /**
     * 沿 cause 链找「superseded_by 列不存在」的证据。
     * <p>
     * 限深 10 层：足够覆盖 MyBatis → Spring → JDBC 的包装链，
     * 又能防御病态的自引用 cause 链导致的死循环。
     */
    private static boolean isMissingSupersededColumn(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 10; depth++) {
            String message = current.getMessage();
            if (message != null
                    && message.contains("superseded_by")
                    && (message.contains("does not exist") || message.contains("不存在"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public List<ChatHistory> getByMemoryId(Object memory) {
//        输入任意字符，则过滤工具消息
        return mapper.getAllByMemoryId(memory);
    }

    @Override
    public List<ChatHistory> getByMemoryIdAndUserId(Object memory, Long userId) {
//        对话链路读记忆专用：必须带 user_id，否则会读到别人的会话
        return queryAllForHistory(memory, userId);
    }

    @Override
    public List<ChatHistory> getRecentForChat(Object sessionId, Long userId, int limit) {
//        ⚠️ limit <= 0 必须在这里分流：SQL 的 LIMIT 0 会返回 0 行、LIMIT 负数直接报错，
//        而调用方的语义是「不限制」。别把它透传进 SQL。
        if (limit <= 0) {
            return mapper.getAllByMemoryIdAndUserId(sessionId, userId);
        }
        return mapper.getRecentForChat(sessionId, userId, limit);
    }

    @Override
    public List<ChatHistory> getActiveForChat(Object sessionId, Long userId, int limit) {
        return queryActiveForMemory(sessionId, userId, limit);
    }

    @Override
    public int markSupersededSince(Object sessionId, Long userId, Long sinceId,
                                   Long newId, String excludeRunId) {
        if (sessionId == null || userId == null || sinceId == null || newId == null) {
            // 参数不全是「不是重新生成」而不是错误：普通发问不该走到有值的分支
            return 0;
        }
        return mapper.markSupersededSince(sessionId, userId, sinceId, newId, excludeRunId);
    }

    @Override
    public Long findFirstAiMessageIdOfRun(Object sessionId, Long userId, String runId) {
        if (sessionId == null || userId == null || runId == null) {
            return null;
        }
        return mapper.findFirstAiMessageIdOfRun(sessionId, userId, runId);
    }

    @Override
    public boolean existsByRunId(Object sessionId, Long userId, String runId) {
        if (sessionId == null || runId == null) {
            return false;
        }
        return mapper.existsByRunId(sessionId, userId, runId);
    }

    @Override
    public String getLastMessageJson(Object sessionId, Long userId) {        return mapper.getLastContentByMemoryId(sessionId, userId);
    }

    @Override
    public List<String> getRecentMessageJson(Object sessionId, Long userId, int limit) {
        return mapper.getRecentContents(sessionId, userId, limit);
    }

    @Override
    public void delByMemoryId(Object memoryId) {
        mapper.delAllByMemoryId(memoryId);
    }

    @Override
    public void insertBatch(List<ChatHistory> list,Long userId) {
        if (list.isEmpty()){
            log.debug("插入失败,消息为空");
            return;
        }
        mapper.insertBatch(list, userId);
    }
    @Override
    public int getCountBySessionID(String sessionId) {
        return mapper.getCountByMemoryId(sessionId);
    }

    @Override
    public List<ChatMemorySearchHit> searchHits(Long userId, String pattern, int limit) {
        // userId 由调用方保证非空（会话搜索走的是已登录接口）；pattern/limit 也已在那边收敛过
        return mapper.searchHits(userId, pattern, limit);
    }

    @Override
    public List<MessageVO> getHistoryBySessionId(String sessionId) {
//        越权修复：对外读取历史必须限定当前登录用户，否则知道 sessionId 就能读他人会话
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("用户未登录！");
        }
        List<ChatHistory> chatHistories = queryAllForHistory(sessionId, userId);
        if (chatHistories==null||chatHistories.isEmpty()) return List.of();
//        逐行处理（2026-10-05 改）：产物归属要求**每一行**历史带上它自己的 runId，
//        所以不能再「先映射成 ChatMessage 列表、再统一转 VO」——那样行上的 runId 就丢了。
        List<MessageVO> result = new ArrayList<>(chatHistories.size());
        for (ChatHistory entity : chatHistories) {
            ChatMessage msg = toChatMessage(entity);
            if (msg == null) {
                continue;
            }
            MessageVO vo = toMessageVO(msg);
            if (vo == null) {
                continue;
            }
//            老数据（run_id 列上线前写的行）这里就是 null，前端按「归属不明」跳过
            vo.setRunId(entity.getRunId());
//            2026-10-06：带上 id 与「是否已被更新版本替代」。
//            id 是前端做 n/n 版本切换的 key（不能用下标，切换会错位）；
//            supersededBy 让前端知道默认该展开哪一版。被替代的行**照常返回** ——
//            不返回就没法做切换了，它只是不参与模型上下文。
            vo.setId(entity.getId());
            vo.setSupersededBy(entity.getSupersededBy());
//            2026-10-07：历史里工具 id 缺失时合成一个唯一 id（见 ensureToolIds 注释）
            ensureToolIds(vo, entity.getId());
            result.add(vo);
        }
        return result;
    }

    /**
     * 一行历史 → 一条 langchain4j 消息。脏数据返回 {@code null}（已记日志），由调用方跳过。
     */
    private ChatMessage toChatMessage(ChatHistory entity) {
        // content 是 jsonb 列，历史脏数据可能为 null：跳过而不是让整段会话 500
        if (entity.getContent() == null) {
            log.warn("历史消息 content 为空，已跳过。id={}", entity.getId());
            return null;
        }
        String content = entity.getContent().toString();
        if ("TOOL_EXECUTION_RESULT".equals(entity.getType())) {
            try {
                content = toStandardToolExecutionResult(content);
            } catch (JsonProcessingException e) {
                // 单条工具结果解析失败不该连累整段历史：记日志后用原始内容兜底
                log.warn("TOOL_EXECUTION_RESULT 标准化失败，回退原始内容。id={}", entity.getId(), e);
            }
        }
        return ChatMessageDeserializer.messageFromJson(content);
    }

    /**
     * 一条消息 → 前端要的 VO。未知类型返回 {@code null}（不出现在历史里）。
     */
    private MessageVO toMessageVO(ChatMessage msg) {
            MessageVO.MessageVOBuilder messageVOBuilder = MessageVO
                    .builder();
            switch (msg) {
                case UserMessage userMessage -> {
                    messageVOBuilder.type(MessageType.USER);
//                    这里要考虑之后，图文并发的时候，怎么处理
//                    暂时只考虑单文本
//                    解决方案：添加标识符区分

                    if (userMessage.hasSingleText()) {
                        String text = userMessage.singleText();
                        /*这里获取到了:
                        UserMessage { name = null, contents = [TextContent { text = "text" }], attributes = {} }
                        要进行转换
                        */
                        messageVOBuilder.content(stripLegacyWrappers(text));
                    }else{
//                        说明有文件/图片等其他内容
                        Map<String, Object> attributes = userMessage.attributes();
                        log.debug("attributes:{}",attributes);

//                        附件元数据（可能为 null，用空集合兜底）
                        Object attachedFilesRaw = attributes.get(ATTACHED_FILES);
                        List<AttachedFileVO> attachedFiles = attachedFilesRaw == null
                                ? List.of()
                                : JSONUtil.toList(JSONUtil.toJsonStr(attachedFilesRaw), AttachedFileVO.class);

//                        文本内容：过滤掉文件/图片的包裹片段，只保留用户真正输入的文本。
//                        注意：必须用 instanceof 判断——历史上这里直接强转 TextContent，
//                        遇到图片等非文本内容会抛 ClassCastException。
                        StringBuilder textBuilder = new StringBuilder();
                        for (Content content : userMessage.contents()) {
                            if (!(content instanceof TextContent textContent)) {
//                                图片走 ImageContent（2026-10-03 起）：文本里不再出现，前端用 attachedFiles 渲染
                                continue;
                            }
                            String text = textContent.text();
                            if (isFileOrImageWrapper(text)) {
                                continue;
                            }
                            textBuilder.append(text);
                        }
//                        存量数据可能把文本和包裹标记存在同一个 content 里，统一再剥一次
                        messageVOBuilder
                                .content(stripLegacyWrappers(textBuilder.toString()))
                                .attachedFiles(attachedFiles);
                    }


//                	"contents": [{
                    //		"text": "UserMessage { name = null, contents = [TextContent { text = \"广东职业技术学院张政康的具体信息\" }], attributes = {} }",
                    //		"type": "TEXT"
                    //	}],
                    //	"type": "USER"
                }
                case AiMessage aiMessage -> {
//                    2026-10-07：对前端隐藏的工具，其调用项要从历史里剔除 ——
//                    否则「实时流看不到、刷新页面又冒出来」，等于没屏蔽。
//                    ⚠️ 只过滤这一层的展示：chat_memory 里的原始消息一个字都不动，
//                    模型上下文仍需要完整的 tool_calls（见 ToolVisibility 类注释）。
                    List<MessageVO.ToolRequestVO> requestVOList =
                            aiMessage.toolExecutionRequests() == null
                                    ? List.of()
                                    : aiMessage.toolExecutionRequests().stream()
//                                    并行调用时一条 AiMessage 可能带多个工具请求，
//                                    只剔隐藏的那些，可见的照常留下（不能整条丢，那会连正文一起丢）
                                    .filter(request -> !isHiddenTool(request == null ? null : request.name()))
                                    .map(request -> MessageVO.ToolRequestVO.builder()
                                            .toolName(request.name())
                                            .id(request.id())
                                            .arguments(request.arguments()).build())
                                    .toList();
//                    整条消息只剩隐藏工具（无正文、无思考、无可见调用）时不返回 ——
//                    否则前端会渲染出一个空气泡
                    if (requestVOList.isEmpty() && isBlank(aiMessage.text()) && isBlank(aiMessage.thinking())) {
                        return null;
                    }
                    messageVOBuilder.type(MessageType.AI);
                    messageVOBuilder.content(aiMessage.text());
                    messageVOBuilder.thinking(aiMessage.thinking());
                    messageVOBuilder.toolRequestList(requestVOList);
                }
                case ToolExecutionResultMessage toolResult -> {
//                    与上面成对：请求被剔掉时，结果行也必须剔掉（否则前端收到孤儿结果）
                    if (isHiddenTool(toolResult.toolName())) {
                        return null;
                    }
                    messageVOBuilder.type(MessageType.TOOL_EXECUTION_RESULT);
                    MessageVO.ToolResultVO resultVO = MessageVO.ToolResultVO.builder()
                            .isError(toolResult.isError())
                            .result(toolResult.text())
                            .toolName(toolResult.toolName())
                            .id(toolResult.id())
                            .build();
                    messageVOBuilder.toolResultVO(resultVO);
                }
                default -> {
                    log.info("其他类型，暂时不处理");
                    return null;
                }
            }
            return messageVOBuilder.build();
    }


    /**
     * 🔴 工具 id 缺失时合成一个**唯一** id（2026-10-07，frontend 踩坑后补的兜底）。
     * <p>
     * <b>为什么必须有这一层</b>：{@code toolRequestList[].id} 与 {@code toolResultVO.id}
     * 都是**模型/供应商给的字符串**，我们原样透传 —— 它可能为 {@code null}，
     * 也可能在同一批里重复（某些供应商的兼容层压根不回传 tool_call id）。
     * frontend 用 {@code call.id} 做 {@code v-for} 的 key，id 撞车时 Vue 的 patch
     * 拿到 null el，抛 {@code Cannot set properties of null (setting '__vnode')}，
     * 结果是整个应用渲染停摆（点历史记录后点什么都没反应）。
     * <p>
     * 形如 {@code row-1002#0}：带行主键，<b>全局唯一</b>（不只是同一条消息内唯一），
     * 所以前端即便把不同消息的工具卡铺进同一个列表也不会撞。
     * <p>
     * ⚠️ 只补**展示层**，不动 {@code chat_memory}：记忆里那条消息要保持原样，
     * 否则模型下一轮收到的 tool_call id 与它自己发的不一致（且会破坏配对语义）。
     */
    private static void ensureToolIds(MessageVO vo, Long rowId) {
        List<MessageVO.ToolRequestVO> requests = vo.getToolRequestList();
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                MessageVO.ToolRequestVO r = requests.get(i);
                if (r != null && isBlank(r.getId())) {
                    r.setId(syntheticId(rowId, i));
                }
            }
        }
        MessageVO.ToolResultVO result = vo.getToolResultVO();
        if (result != null && isBlank(result.getId())) {
            result.setId(syntheticId(rowId, 0));
        }
    }

    private static String syntheticId(Long rowId, int index) {
        return "row-" + (rowId == null ? "unknown" : rowId) + "#" + index;
    }

    /**
     * 该工具是否对前端隐藏（2026-10-07）。
     * <p>
     * {@code toolVisibility} 未注入时（单测等）返回 false —— 宁可多显示，也不要把历史吞掉。
     */
    private boolean isHiddenTool(String toolName) {
        return toolVisibility != null && toolVisibility.isHidden(toolName);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isFileOrImageWrapper(String text) {
        return (text.startsWith(FILE_START) && text.endsWith(FILE_END))
                || (text.startsWith(IMAGE_START) && text.endsWith(IMAGE_END));
    }

    private static String toStandardToolExecutionResult(String rawJson) throws JsonProcessingException {
        ObjectNode root =(ObjectNode) MAPPER.readTree(rawJson);
        if (root.has("contents")&& root.get("contents").isArray()) {
            ArrayNode contents = (ArrayNode) root.get("contents");
            if (!contents.isEmpty() &&contents.get(0).has("text")){
                String text = contents.get(0).get("text").asText();
                root.put("text",text);
            }
            root.remove("contents");
        }
        if (!root.has("text")) {
            root.put("text","没有返回值");
        }
        return MAPPER.writeValueAsString(root);
    }
}




