package com.huzhijian.nexusagentweb.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量删除文件的请求体（2026-10-05）。
 * <p>
 * <b>为什么单独搞一个批量接口</b>：统一视图里「全选 → 一次性删掉」是最自然的操作，
 * 让前端发 N 次单条请求会带来三个问题 ——
 * <ol>
 *   <li><b>N-1 次鉴权/中间件往返</b>，还可能被浏览器并发连接数限制卡住；</li>
 *   <li>前端要自己维护「哪些成功了」的集合，中途失败就状态不一致；</li>
 *   <li>逐条删时用户会看到一串一闪而过的 toast，无法汇总。</li>
 * </ol>
 * 交给后端一次处理完，返回逐条结果，前端只渲染一次。
 * <p>
 * <h3>⚠️ {@code ids} 用字符串传（重要）</h3>
 * {@code sys_file.id} 是雪花 ID（19 位十进制），而JS 的 {@code number} 只能精确表示
 * 16 位（{@code Number.MAX_SAFE_INTEGER}）—— 前端如果把 id 当数字处理，
 * 精度会在浏览器里丢掉，回传时就查不到记录了。
 * 本项目已全局把 {@code Long} 序列化成字符串（见 {@code JacksonConfig}），
 * <b>所以 id 到前端手里就是字符串，原样回传即可</b>。
 * <p>
 * 用 {@code List<Long>} 接收即可：Jackson 能把 JSON 字符串 {@code "2107..."}
 * 直接反序列化成 {@code Long}，前端<b>不需要</b>手动 parseInt。
 * <p>
 * <h3>刻意不用 {@code @Schema}</h3>
 * {@code nexus-agent-domain} 模块<b>没有引入 swagger 依赖</b>
 * （只有 {@code nexus-agent-web} 有），本模块所有 DTO/VO 一律不写 Swagger 注解。
 * 字段说明用普通 javadoc，需要对外暴露的接口描述写在 Controller 的
 * {@code @Operation(description = ...)} 上。
 *
 * @see com.huzhijian.nexusagentweb.controller.FileController#batchDelete
 */
@Data
public class BatchDeleteFileDTO {

    /**
     * 单次批量删除的上限。
     * <p>
     * 取 200 是个务实的折中：够覆盖「清空整个面板」，又不会让一次请求
     * 在库里做上万次单条删除、把长事务拖到超时。
     */
    public static final int MAX_BATCH_SIZE = 200;

    /**
     * 待删除的文件 id 列表（<b>字符串形态的雪花 ID，原样回传后端返回的 id 即可</b>）。
     * <p>
     * 上限 {@value #MAX_BATCH_SIZE}：一次删太多会让单次请求持有长事务，
     * 也会让 OSS 逐个删除的时间不可控。超限直接 400，让前端分批。
     * <p>
     * 允许含重复与 {@code null}，Service 层会清洗（去重 + 剔空）。
     */
    @NotEmpty(message = "待删除的文件 id 不能为空")
    @Size(max = MAX_BATCH_SIZE, message = "单次最多删除 200 个文件")
    private List<Long> ids;
}
