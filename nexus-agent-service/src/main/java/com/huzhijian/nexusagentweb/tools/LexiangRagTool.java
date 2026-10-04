package com.huzhijian.nexusagentweb.tools;

import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import com.huzhijian.nexusagentweb.service.LexiangService;
import com.huzhijian.nexusagentweb.tools.registry.AgentToolSet;
import com.huzhijian.nexusagentweb.tools.registry.ToolSelection;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 乐享知识库检索工具。
 * <p>
 * 本工具集是知识库检索的**唯一**实现：2026-10-04 起本地 pgvector 知识库已下线。
 * <p>
 * <b>刻意不实现的</b>（用户明确要求只做检索）：
 * 文件上传到乐享、AI 问答。前者要三步走腾讯云 COS；后者会把用户的模型
 * 配额与乐享内部模型绑定，且用户不需要"由乐享生成答案"这件事。
 * <p>
 * <b>token 相关</b>：access_token 的 2 小时缓存由 {@code LexiangTokenProvider} 负责，
 * 本工具每轮对话调一次检索，绝不能自己去换 token（乐享限频 20 次/10 分钟）。
 *
 * @author 胡志坚
 * @version 1.0
 */
@Slf4j
@Component
public class LexiangRagTool implements AgentToolSet {

    @Override
    public String key() {
        return "lexiang_rag";
    }

    @Override
    public String description() {
        return "乐享知识库检索：检索用户接入的腾讯乐享知识库";
    }

    @Override
    public boolean enabled(ToolSelection selection) {
        return selection.lexiangRagEnabled();
    }

    private final LexiangService lexiangService;
    private final ToolCallGuard toolCallGuard;
    private final RunUserRegistry runUserRegistry;

    public LexiangRagTool(LexiangService lexiangService, ToolCallGuard toolCallGuard,
                           RunUserRegistry runUserRegistry) {
        this.lexiangService = lexiangService;
        this.toolCallGuard = toolCallGuard;
        this.runUserRegistry = runUserRegistry;
    }

    /**
     * 确定本次检索该用哪个用户的凭证。
     * <p>
     * <b>为什么不能直接用 UserContextHolder</b>：工具运行在 LangChain4j 的
     * <b>流式回调线程</b>，不是处理 HTTP 请求的线程，ThreadLocal 在那里必然是 null。
     * （实测表现为：配置明明正确，却一直返回「无法确定当前用户，已按安全策略拒绝检索」。）
     * <p>
     * <b>正确做法</b>：请求线程已把 sessionId → userId 登记进 {@link RunUserRegistry}，
     * 这里用 {@code @ToolMemoryId} 注入的 memoryId（即 sessionId）反查。
     * <p>
     * 保留 ThreadLocal 作兜底：单元测试与非流式的同步调用路径上它仍然有效。
     * <p>
     * <b>拿不到就返回 null，由调用方拒绝，绝不退化成"随便取一个用户"</b> ——
     * 那等于跨用户数据泄露。
     */
    private Long resolveUserId(Object memoryId) {
        Long fromRegistry = runUserRegistry.findUserId(memoryId);
        if (fromRegistry != null) {
            return fromRegistry;
        }
        return UserContextHolder.getUserId();
    }

    /**
     * 工具名显式声明为 {@code lexiang_search}，与本地检索的 {@code rag_search} 区分开，
     * 避免模型在两个知识库之间混淆。
     */
    @Tool(name = "lexiang_search", value = "检索用户的腾讯乐享知识库以回答企业内部问题")
    public String lexiangSearch(@ToolMemoryId Object memoryId,
                                @P("查询语句,提取关键词查询") String query) {
        // 同一会话用同一关键词反复检索没有意义（结果一致），省掉一次外部调用
        String blocked = toolCallGuard.interceptText(memoryId, "lexiang_search",
                ToolCallGuard.fingerprint(query));
        if (blocked != null) {
            return blocked;
        }
        Long userId = resolveUserId(memoryId);
        if (userId == null) {
            // 拿不到用户绝不能退化成"不过滤" —— 那是跨用户数据泄露
            log.warn("乐享知识库检索被拒绝：无法从会话 {} 确定当前用户", memoryId);
            return "乐享知识库检索失败：无法确定当前用户，已按安全策略拒绝检索。";
        }
        try {
            // top_n 传 3，与本地 RagTool 保持一致，控制喂给模型的上下文体积
            List<LexiangApi.SearchHit> hits = lexiangService.search(userId, null, query, 3);
            if (hits == null || hits.isEmpty()) {
                return "乐享知识库中未查询到相关知识片段，你可以修改关键词再次尝试查询";
            }
            StringBuilder result = new StringBuilder("以下是检索到的资料:");
            for (LexiangApi.SearchHit hit : hits) {
                result.append("知识来源:").append(hit.getTitle());
                // url 一并带上：模型可以据此给出可点击的引用，溯源体验比纯文本好很多
                if (hit.getUrl() != null) {
                    result.append("(链接:").append(hit.getUrl()).append(")");
                }
                result.append(hit.getContent());
                result.append("--------结束---------");
            }
            return result.toString();
        } catch (Exception e) {
            // 不抛出：把失败原因作为工具结果回给模型，避免整条流因为检索失败而中断
            log.warn("乐享知识库检索失败: {}", e.getMessage());
            return "乐享知识库检索失败：" + e.getMessage() + "，请勿重复检索同一关键词";
        }
    }
}
