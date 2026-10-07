package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

/**
 * 单条文件上传失败的**机器可读**原因码（2026-10-07 新增）。
 * <p>
 * <b>为什么需要它</b>：fronted 提的验收要求原话是
 * 「返回里必须能区分『类型不支持』与『超出配额』」。
 * 只靠 {@code failReason} 的中文文案区分是不可靠的 —— 前端得去匹配字符串，
 * 文案一改（或本地化）判断就失效，而这两种失败的处理方式完全不同：
 * <ul>
 *   <li><b>类型不支持</b>：文件根本没传，让用户换格式；</li>
 *   <li><b>超出配额</b>：要引导用户清理文件 / 等重置，换个格式没用。</li>
 * </ul>
 * 有了这个码，前端写 {@code if (failCode === 'UNSUPPORTED_TYPE')} 就行。
 * <p>
 * ⚠️ <b>它与 {@link UploadStatus} 是两个维度</b>，不要合并：
 * {@code UploadStatus} 回答「成功还是失败」（成功时是 SUCCESS），
 * 本枚举回答「失败在哪一步」（只在 {@code FAILED} 时有值）。
 * 成功时本字段为 {@code null}。
 */
public enum UploadFailCode {

    /** 扩展名不在白名单内 —— 文件根本没往 OSS 传 */
    UNSUPPORTED_TYPE("UNSUPPORTED_TYPE"),

    /** 超出文件/产物配额 —— 换格式没用，要清理或等周期重置 */
    QUOTA_EXCEEDED("QUOTA_EXCEEDED"),

    /** 单次请求文件数超过上限（{@code nexus.agent.upload.max-count}） */
    TOO_MANY_FILES("TOO_MANY_FILES"),

    /** 单文件超过大小上限（正常由 Spring 在解析阶段拦成 413，这里兜底） */
    FILE_TOO_LARGE("FILE_TOO_LARGE"),

    /** OSS / IO 层面的失败（凭证、网络、读流失败等）—— 可重试 */
    UPLOAD_FAILED("UPLOAD_FAILED"),

    /** 兜底：不该出现，出现即说明有分支忘了给码 */
    UNKNOWN("UNKNOWN");

    @Getter
    private final String value;

    UploadFailCode(String value) {
        this.value = value;
    }
}
