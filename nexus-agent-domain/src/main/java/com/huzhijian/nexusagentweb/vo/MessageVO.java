package com.huzhijian.nexusagentweb.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huzhijian.nexusagentweb.em.MessageType;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/3/29
 * 说明:
 */
@Builder
@Data
public class MessageVO {
    /**
     * 本条消息的 id（{@code chat_memory.id}，雪花 ID）。
     * <p>
     * 🔴 <b>2026-10-06 新增</b>：前端做「重新生成」的 n/n 版本切换时<b>必须</b>用这个做 key
     * —— 不能用数组下标，切换时列表顺序会变，下标会把两个版本搞混。
     * <p>
     * 序列化后是<b>字符串</b>（全局把 {@code Long} 序列化成 String）：19 位数字超出
     * JS 的 {@code Number.MAX_SAFE_INTEGER}（16 位），当初文件删除失效就是踩了这个。
     * 用它当 key、比较相等都没问题，但<b>不要</b> {@code Number()} / {@code parseInt()}。
     * <p>
     * 老数据同样有值（id 是表主键，从一开始就有），不是新增语义。
     */
    private Long id;

    private MessageType type;
    private String content;
    private String thinking;
    private List<AttachedFileVO>  attachedFiles;
//    private UserMessageVO userMessageVO;
//    工具执行结果
    private ToolResultVO toolResultVO;
//    工具请求参数
    private List<ToolRequestVO> toolRequestList;
    /**
     * 产物（P2-10）：AI 产出的交付物，前端据此渲染「下载卡片」。
     * <p>
     * 结构：{@code {id, name, url, size, extension, sourcePath}}；
     * {@code id} 是 {@code sys_file} 主键（用于去重与追溯），随 SSE {@code artifact} 事件下发。
     */
    private Map<String, Object> artifact;

    /**
     * 本行消息属于「哪一次运行」（产物归属，方案 B）。
     * <p>
     * 取值就是 SSE 信封里那个 {@code runId}，落库在 {@code chat_memory.run_id}。
     * 前端做两件事：
     * <ol>
     *   <li>{@code GET /api/artifact?sessionId=} 拿到的产物也带 {@code runId}；</li>
     *   <li>{@code artifact.runId === message.runId} → 把该产物内联到这条消息末尾。</li>
     * </ol>
     * <b>老数据为 {@code null}</b>（run_id 列上线之前写的行），前端跳过即可 ——
     * 不要靠时间或顺序去猜，猜错会把产物挂到没产出它的那一轮，比不显示更糟。
     */
    private String runId;

    /**
     * 非空 = 这条 AI 回答已被更新的回答替代，<b>只</b>用于前端的 n/n 版本切换显示。
     * <p>
     * 🔴 <b>2026-10-06 新增</b>（{@code docs/sql/013_add_superseded_by.sql}）。
     * 值是替代者的 {@link #id}。
     * <p>
     * 前端要知道的只有两件事：
     * <ol>
     *   <li>哪些回答属于同一个问题的不同版本 → 靠「同一条 USER 消息后连续的 AI 消息」分组；</li>
     *   <li>默认展开哪一个 → <b>展开 {@code supersededBy == null} 的那个</b>（当前生效的版本）。</li>
     * </ol>
     * ⚠️ 被替代的行<b>照常返回</b>，只是不参与模型上下文 —— 不返回的话前端就做不了切换。
     * <p>
     * 老数据与用户提问行都是 {@code null}，按「当前版本」处理即可。
     */
    private Long supersededBy;
    @Data
    @Builder
    public static class UserMessageVO{
        /**
         * "contents": [{
            "text": "UserMessage { name = null, contents = [TextContent { text = \"广东职业技术学院张政康的具体信息\" }], attributes = {} }",
            "type": "TEXT"
             }],
         "type": "USER"
         */
        private String id;
        private String toolName;
        private Object arguments;
    }
    @Data
    @Builder
    public static class ToolRequestVO{
        /**
         "toolExecutionRequests": [{
         "id": "call_58627d966c2d42828dc0e04b",
         "name": "getDate",
         "arguments": "{}"
         }],
         *
         */
        private String id;
        private String toolName;
        private Object arguments;
        /**
         * 同批并行调用里的**序号**（2026-10-07 新增，来自模型流式帧的 {@code index}）。
         * <p>
         * 🔴 <b>为什么前端不该只拿 {@link #id} 做列表 key</b>：
         * {@code id} 是模型/供应商给的，<b>可能为 null、也可能重复</b> ——
         * 尤其流式帧里除首帧外 id 常常不带，而历史行里某些供应商根本不给 id。
         * frontend 就踩过：工具卡列表用 {@code call.id} 做 {@code v-for} key，
         * id 缺失/重复导致 key 撞车，Vue patch 拿到 null el 抛
         * {@code Cannot set properties of null (setting '__vnode')}，整个应用渲染停摆。
         * <p>
         * {@code index} 在同一批调用里<b>稳定且唯一</b>（首帧就有，不会为 null），
         * 要做 key 请优先用它（或 {@code 消息id + index}）；{@code id} 只用于
         * 与 {@code tool_execution_result} 配对 —— 而配对也可能配不上（见契约文档）。
         * <p>
         * ⚠️ 仅 SSE 的 {@code tool_execution} 事件带它；<b>历史接口没有</b> ——
         * 历史来自 {@code chat_memory} 的序列化消息，langchain4j 的
         * {@code ToolExecutionRequest} 不含 index（历史侧改为后端兜底合成 id，见
         * {@code ChatMemoryServiceImpl#ensureToolIds}）。
         */
        private Integer index;
    }
    @Data
    @Builder
    public static class ToolResultVO{
        /**
         * {"id":"call_58627d966c2d42828dc0e04b",
         * "toolName":"getDate","text":"2026/03/28",
         * "isError":false,"attributes":{},"type":"TOOL_EXECUTION_RESULT"}
         *
         */
        private String id;
        private String toolName;
        private String result;
        private Boolean isError;

        /**
         * 结构化来源（2026-10-08 新增，<b>可选</b>）：目前只有 {@code web_search} 的结果会带。
         * <p>
         * 用途：前端渲染「已搜索 N 个来源 + 来源卡片 + 正文 [1] 角标」那条 UI。
         * 元素形态见 {@code docs/sse-contract.md} 的 {@code tool_execution_result} 小节：
         * {@code {index:int, title:string, url:string, snippet:string}}。
         * <p>
         * ⚠️ 为什么它与 {@code result} 是两份而不是合并：
         * {@code result} 是要进<b>模型上下文与历史消息</b>的纯文本，而本字段只给 UI ——
         * 混进去等于每次搜索多烧一份 token，且会把历史消息撑大。
         * <p>
         * ⚠️ <b>仅在实时流里下发</b>：历史消息由 {@code ChatMemoryServiceImpl} 从库里恢复，
         * 库里只存了工具结果的文本，**没有**这份结构 —— 所以刷新页面后来源卡片不在，
         * 前端必须容忍该字段缺失（这是已知取舍，不是 bug）。
         * <p>
         * 🔴 {@code index} 必须是 {@code int} 而非 {@code Long}：全局 {@code JacksonConfig}
         * 会把 {@code Long}/{@code long} 序列化成字符串（雪花 ID 精度），
         * 写成 {@code Long} 前端就会收到 {@code "1"}（2026-10-08 {@code ttfbMs} 同源坑）。
         * <p>
         * {@code NON_NULL}：绝大多数工具没有来源，不加这行每个工具结果都会多一个
         * {@code "sources": null}，既难看又逼前端多写一个 falsy 判断。
         * 「字段不存在」比「存在但为 null」对前端更省事（契约里也是这么写的）。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<Map<String, Object>> sources;
    }
}
