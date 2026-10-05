package com.huzhijian.nexusagentweb.em;

import java.util.Arrays;
import java.util.Locale;

/**
 * 用户角色（2026-10-05 新增，配套 {@code docs/sql/012_add_user_role_and_file_quota.sql}）。
 * <p>
 * <b>三档</b>：
 * <ul>
 *   <li>{@link #NORMAL} 普通用户 —— 正常注册即为此档：每日 100 万 token、每日 100 个文件与产物；</li>
 *   <li>{@link #TEST} 测试用户 —— 每日 1000 万 token、文件与产物<b>不限制</b>；</li>
 *   <li>{@link #VIP} 会员用户 —— <b>预留</b>，当前不对外发放：每日 1000 万 token、每日 1000 个文件与产物。</li>
 * </ul>
 * <p>
 * <b>为什么用枚举而不是全靠配置</b>：这三档是产品形态，不是部署参数 ——
 * 写死在代码里才能让"每个角色代表什么"只有一个答案；
 * 真要给某个用户临时加额度，直接在库里覆盖 {@code users.token_quota} / {@code users.file_quota}
 * 即可（用户级列优先于角色默认值，见 {@code QuotaServiceImpl}）。
 * <p>
 * ⚠️ <b>文件/产物为 {@code FILE_UNLIMITED} 时表示不限制</b>，不要写成 0：
 * 0 会被当成"配额是 0 却还能传"，排查时非常绕。
 */
public enum UserRole {

    /** 普通用户：正常注册 */
    NORMAL(1_000_000L, 100L),

    /** 测试用户：额度放大、文件与产物不限 */
    // ⚠️ 必须写成 UserRole.FILE_UNLIMITED（全限定名）而不是 FILE_UNLIMITED：
    //    枚举常量的实参里用简单名引用本类后声明的静态字段属于「非法前向引用」，编译不过。
    //    值是编译期常量，加不加限定都不会读到 0。
    TEST(10_000_000L, UserRole.FILE_UNLIMITED),

    /** 会员用户（预留，暂不发放） */
    VIP(10_000_000L, 1000L);

    /** 文件/产物「不限制」的哨兵值。刻意不给 Long.MAX_VALUE，免得参与算术时溢出。 */
    public static final long FILE_UNLIMITED = -1L;

    private final long dailyTokens;
    private final long dailyFiles;

    UserRole(long dailyTokens, long dailyFiles) {
        this.dailyTokens = dailyTokens;
        this.dailyFiles = dailyFiles;
    }

    /** 该角色每日 token 上限 */
    public long dailyTokens() {
        return dailyTokens;
    }

    /** 该角色每日文件 + 产物上限；{@link #FILE_UNLIMITED} 表示不限制 */
    public long dailyFiles() {
        return dailyFiles;
    }

    public boolean filesUnlimited() {
        return dailyFiles == FILE_UNLIMITED;
    }

    /**
     * 解析角色名（忽略大小写与空白）。
     *
     * @return 无法识别时回退 {@link #NORMAL} —— 角色只是"额度档位"，
     *         解析不出来降级到最保守的一档，好过让注册/对话直接失败。
     */
    public static UserRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElse(NORMAL);
    }
}
