package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.vo.UserMemoryVO;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/1
 * 说明: 用户长期记忆工具。
 * <p>
 * 注意职责边界：知识库检索已拆分到 {@link LexiangRagTool}，
 * 本类只负责"用户长期记忆"的检索与写入，不要在这里再加 RAG 相关方法。
 */
@Component
@Slf4j
public class MemoryTool implements AgentToolSet {

    @Override
    public String key() {
        return "memory";
    }

    @Override
    public String description() {
        return "用户长期记忆：检索用户画像、保存用户偏好";
    }

    private final UserMemoryService memoryService;
    private final ToolCallGuard toolCallGuard;
    /**
     * 🔴 工具跑在 LangChain4j 的**流式回调线程**上，那里 {@link UserContextHolder}
     * 必然取不到值（这不是偶发，是架构上的必然）。要拿 userId 只能靠
     * 「请求线程登记的 sessionId → userId」反查表，见 {@link RunUserRegistry}。
     */
    private final RunUserRegistry runUserRegistry;
    private final String SAVE_USER_MEMORY= """
            用于主动保存用户的长期记忆。
            
            【核心要求】
            1. 必须极度精简，去掉废话和冗余。
            2. 严禁包含“用户”或“他/她”等主语（默认主语即为用户本人）。
            3. 仅保留最核心的属性或偏好，无需展开过于具体的细节列表
            4. 记忆内容是供AI后续读取的，请使用客观、简练的断言式短语，尽量一句话说明白。
            
            【长期记忆范畴】
            - 长期稳定的喜好或厌恶（如：喜欢看科幻片、不喜欢吃辣）
            - 长期习惯或作息（如：习惯晚睡、每周五健身）
            - 长期身份信息（如：职业是程序员、现居北京）
            - 长期目标（如：正在准备考研）
            - 持续性的客观事实（如：养了一只英短猫）
            
            如果执行失败，禁止重复尝试！
            """;
    public MemoryTool(UserMemoryService memoryService, ToolCallGuard toolCallGuard,
                      RunUserRegistry runUserRegistry) {
        this.memoryService = memoryService;
        this.toolCallGuard = toolCallGuard;
        this.runUserRegistry = runUserRegistry;
    }

    /**
     * 取本次运行的 userId。
     * <p>
     * 🔴 <b>绝不能用 {@code UserContextHolder.getUserId()}</b>（2026-10-05 修的线上故障）：
     * 工具在流式回调线程上执行，那里 ThreadLocal 恒为 null。
     * 以前这里取到 null 后照常写库，撞上 {@code user_memory.user_id NOT NULL}，
     * 而 {@code saveMemory} 又是 {@code @Async} 的 —— 异常在异步线程里被吞，
     * 工具照样返回 {@code "ok"}，用户查库却一条都没有。
     *
     * @return userId；查不到返回 null，调用方必须**报错**而不是退化放行
     */
    private Long requireUserId(Object memoryId) {
        Long userId = runUserRegistry.findUserId(memoryId);
        if (userId == null) {
            log.warn("长期记忆工具取不到 userId（工具线程无登录态，且注册表未命中）：memoryId={}", memoryId);
        }
        return userId;
    }

    /**
     * P2-7：@P 的措辞刻意改成"1~2 个核心词"。
     * 以前只写"关键字"，模型经常把整句话丢进来（"用户喜欢吃什么口味的菜"），
     * 而检索是字面匹配，整句必然零命中 —— 提示词里说清楚能省掉一次无效调用。
     */
    @Tool(name = "search_user_memory", value = "检索用户画像")
    public String searchUserMemory(@ToolMemoryId Object memoryId,
                                   @P("检索关键词，**只用 1~2 个核心词**（如\"饮食偏好\"\"职业\"），不要传整句话") String query) {
        String blocked = toolCallGuard.interceptText(memoryId, "search_user_memory",
                ToolCallGuard.fingerprint(query));
        if (blocked != null) {
            return blocked;
        }
        try {
            Long userId = requireUserId(memoryId);
            if (userId == null) {
                return "检索长期记忆失败：无法确定当前用户（会话上下文缺失）。请勿重复调用该工具。";
            }
            List<UserMemoryVO> memory = memoryService.getMemory(userId, query);
            if (memory == null || memory.isEmpty()) {
                // 以前返回空串，模型无法区分"没查到"和"查到了但内容为空"
                return "没有检索到与该关键词相关的长期记忆。";
            }
            // 以前是无分隔符硬拼接（"不吃辣喜欢科幻片"），模型很难切分、还会误读成一条
            return memory.stream()
                    .map(UserMemoryVO::getContent)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .map(s -> "- " + s)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            // 工具返回值会直接进模型上下文：既要记日志（否则线上无从追查），也不能把 null 丢给模型
            log.error("检索长期记忆失败。query={}", query, e);
            return "检索长期记忆失败：" + Objects.toString(e.getMessage(), e.getClass().getSimpleName())
                    + "。请勿重复调用该工具。";
        }
    }

    @Tool(name = "save_user_data",value = SAVE_USER_MEMORY)
    public String saveLongMemory(@ToolMemoryId Object memoryId,
                                 @P("记忆内容，需要符合核心要求") String content,
                                 @P(value = "会话ID",required = false) String sessionId){
        // 重复写入会产生重复记忆数据（污染后续检索），所以这里也要拦
        String blocked = toolCallGuard.interceptText(memoryId, "save_user_data",
                ToolCallGuard.fingerprint(content));
        if (blocked != null) {
            return blocked;
        }
//        🔴 不要用 UserContextHolder：工具线程上它恒为 null（见 requireUserId 的注释）。
//        取不到用户就**明确报错**，绝不能退化成"随便存一条" —— 那会写出 user_id 为空的脏数据。
        Long userId = requireUserId(memoryId);
        if (userId == null) {
            return "error:无法确定当前用户，本次记忆未保存（会话上下文缺失）。请勿重复调用该工具。";
        }
        log.info("保存长期记忆：userId={} content={}", userId, content);
        UserMemory userLongMemory =  UserMemory.builder().content(content).userId(userId).source(sessionId).build();
        try {
            memoryService.saveMemory(userLongMemory);
        } catch (Exception e) {
            log.error("保存长期记忆失败。userId={} content={}", userId, content, e);
            return "error:"+e.getMessage();
        }
        return "ok";
    }
}
