package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量删除的结果（2026-10-05）。
 * <p>
 * <b>刻意做成「部分成功」而不是全有全无</b>：一批 20 个文件里，
 * 可能有一个已经被别处删掉了、或压根不属于当前用户。
 * 如果这种情况让整个请求失败，用户就得反复重试、
 * 且永远不知道自己那19 个到底删没删掉。所以：
 * <ul>
 *   <li>能删的都删，返回 {@code deletedIds}；</li>
 *   <li>删不掉的进 {@code failedIds}，<b>不区分「不存在」和「不是你的」</b>
 *       （区分就成了探测他人文件的探针）。</li>
 * </ul>
 * <p>
 * 前端拿到后按 {@code deletedIds} 把行从面板里移除，{@code failedIds} 保持原样即可 ——
 * <b>不要</b>因为有 failed 就整页报错。
 * <p>
 * <h3>关于 id 的类型</h3>
 * 这两个列表都是 {@code String}：雪花 ID 有 19 位，JS 的 number 存不下
 * （见 {@code JacksonConfig}）。前端拿到的就是字符串，
 * <b>与列表接口返回的 id 形态完全一致</b>，可以直接拿去比对。
 * <p>
 * <h3>刻意不用 {@code @Schema}</h3>
 * {@code nexus-agent-domain} 模块<b>没有引入 swagger 依赖</b>
 * （只有 {@code nexus-agent-web} 有），本模块所有 VO 一律不写 Swagger 注解。
 * 接口描述写在 Controller 的 {@code @Operation(description = ...)} 上。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchDeleteResultVO {

    /** 请求删除的 id 总数（已去重、已剔除 null） */
    private Integer total;

    /** 实际删除成功的数量 */
    private Integer successCount;

    /** 未删除的数量（不存在 / 不属于当前用户） */
    private Integer failCount;

    /** 删除成功的 id 列表（字符串形态雪花 ID，与列表接口的 id 完全一致） */
    private List<String> deletedIds;

    /** 未删除的 id 列表；不区分「不存在」与「不属于你」 */
    private List<String> failedIds;
}
