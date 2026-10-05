package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前用户的 token 配额用量（P2-8 遗留，供 {@code GET /api/user/quota} 返回）。
 * <p>
 * 刻意把「是否不限量」做成显式字段 {@code unlimited}，而不是让前端去猜
 * {@code quota == null} 是什么意思 —— 前端最容易写错的就是这种隐含约定。
 * <p>
 * {@code degraded} 为真时表示**配额信息不可用**（典型原因：`docs/sql/003` 或 `007` 没执行，
 * 查询直接报缺列）。此时其余字段不可信，前端应提示"配额信息暂不可用"而不是显示 0。
 * 之所以不抛异常：配额是**附加能力**，不该让一个没跑迁移的环境连「设置页」都打不开。
 */
@Data
@Builder
public class QuotaVO {
    /**
     * 配额上限；{@code null} 表示不限制。
     */
    private Long quota;
    /** 当前周期内的已用量；不限量时也是有意义的（统计口径） */
    private Long used;
    /** 不限制时剩余量没有意义，返回 {@code null} */
    private Long remaining;
    /** 是否不限量（{@code quota} 为 null 或 &lt;= 0） */
    private boolean unlimited;
    /** 重置周期：{@code NONE} / {@code DAILY} / {@code MONTHLY} */
    private String period;
    /** 当前周期的起点；{@code NONE} 时为 {@code null} */
    private LocalDateTime periodStart;
    /** 配额信息是否不可用（见类注释） */
    private boolean degraded;

    // ==================== 以下为 docs/sql/012 用户分级新增 ====================

    /**
     * 用户角色：{@code NORMAL} / {@code TEST} / {@code VIP}。
     * <p>
     * 前端只做展示（例如头像旁一个角标）；**不要**用它判断能不能上传 ——
     * 能不能上传一律看 {@code fileUnlimited} / {@code fileRemaining}，
     * 因为运营可以单独给某人改 {@code users.file_quota}，那时角色与额度并不一致。
     */
    private String role;

    /**
     * 今日文件与产物的条数上限；{@code null} 表示不限制。
     * <p>
     * 「文件与产物」在库里是同一张 {@code sys_file}，所以共用一个额度 ——
     * 与产品口径一致（普通用户每天 100 个文件 + 产物）。
     */
    private Long fileQuota;

    /** 今日已产生的文件 + 产物条数（不限量时也有统计意义） */
    private Long fileUsed;

    /** 今日剩余可产生的条数；不限量时为 {@code null} */
    private Long fileRemaining;

    /** 文件与产物是否不限量 */
    private boolean fileUnlimited;

    /** 配额信息不可用时返回的占位值 */
    public static QuotaVO degraded() {
        return QuotaVO.builder()
                .unlimited(true)
                .fileUnlimited(true)
                .period("NONE")
                .degraded(true)
                .build();
    }
}
