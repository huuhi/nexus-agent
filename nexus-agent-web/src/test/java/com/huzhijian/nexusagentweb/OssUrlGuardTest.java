package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AliOssProperties;
import com.huzhijian.nexusagentweb.utils.OssUrlGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OssUrlGuard} 的纯单测 —— 附件越权读的回归网。
 * <p>
 * <b>对应的漏洞（P1）</b>：聊天的 {@code fileUrl} 由前端传，服务端拿它用<b>自己的</b>
 * OSS 凭证下载并解析成文本喂给模型。修复前只从 URL 里抠 path 当 key，
 * 于是任何登录用户填上别人的文件地址就能读走别人的文件 ——
 * bucket 私有也没用，因为校验的是"服务端有没有权限"，不是"文件是不是你的"。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@DisplayName("OssUrlGuard —— 附件 URL 必须落在自家 OSS 且属于当前用户")
class OssUrlGuardTest {

    private static final Long USER_ID = 1001L;
    private static final String HOST = "nexus-agent-file.oss-cn-guangzhou.aliyuncs.com";

    private OssUrlGuard guard;

    @BeforeEach
    void setUp() {
        AliOssProperties props = new AliOssProperties();
        props.setEndpoint("https://oss-cn-guangzhou.aliyuncs.com");
        props.setBucketName("nexus-agent-file");
        props.setFileDir("user/file");
        props.setImageDir("user/avatar");
        guard = new OssUrlGuard(props);
    }

    @Nested
    @DisplayName("validateOwnedBy —— 文档归属")
    class OwnedBy {

        @Test
        @DisplayName("自己的文件：放行")
        void allowsOwnFile() {
            assertDoesNotThrow(() -> guard.validateOwnedBy(
                    "https://" + HOST + "/user/file/user_1001/2026/10/04/abc.pdf", USER_ID, "解析附件"));
        }

        @Test
        @DisplayName("别人的文件：拒绝")
        void rejectsOtherUsersFile() {
            ValidationException ex = assertThrows(ValidationException.class, () -> guard.validateOwnedBy(
                    "https://" + HOST + "/user/file/user_1002/2026/10/04/secret.pdf", USER_ID, "解析附件"));
            // 措辞刻意是"不存在或不属于"，不告诉攻击者"文件确实存在，只是不是你的"
            assertTrue(ex.getMessage().contains("不属于") || ex.getMessage().contains("不存在"),
                    "实际是：" + ex.getMessage());
        }

        @ParameterizedTest(name = "前缀混淆必须拒绝：{0}")
        @ValueSource(strings = {
                // 都在 user_1001 附近，但都不是 user_1001 的目录
                "https://" + HOST + "/user/file/user_1001_evil/2026/10/04/x.pdf",   // 尾缀冒充
                "https://" + HOST + "/user/file/user_10012/2026/10/04/x.pdf",          // 多一位数字
                "https://" + HOST + "/user/file/user_101/2026/10/04/x.pdf",           // 少一位
                "https://" + HOST + "/user/file/x/user_1001/2026/10/04/x.pdf",         // 层级不对
                "https://" + HOST + "/user/file/../user_1001/x.pdf",                  // 路径穿越
                "https://" + HOST + "/user/avatar/../../user/file/user_1001/x.pdf"
        })
        void rejectsPrefixConfusion(String url) {
            assertThrows(ValidationException.class, () -> guard.validateOwnedBy(url, USER_ID, "解析附件"));
        }

        @Test
        @DisplayName("query / fragment 不影响归属判定（OSS 签名 URL 一定带 query）")
        void toleratesQueryString() {
            assertDoesNotThrow(() -> guard.validateOwnedBy(
                    "https://" + HOST + "/user/file/user_1001/2026/10/04/abc.pdf"
                            + "?x-oss-signature=deadbeef&x-oss-expires=1800",
                    USER_ID, "解析附件"));
        }

        @Test
        @DisplayName("未登录直接拒，不给\"匿名读文件\"留口子")
        void rejectsNullUserId() {
            assertThrows(ValidationException.class, () -> guard.validateOwnedBy(
                    "https://" + HOST + "/user/file/user_1001/x.pdf", null, "解析附件"));
        }
    }

    @Nested
    @DisplayName("validateHost —— 图片（目录里没有 userId，只校验域名）")
    class Host {

        @Test
        @DisplayName("自家 OSS 上的图片：放行")
        void allowsOwnImage() {
            assertDoesNotThrow(() -> guard.validateHost(
                    "https://" + HOST + "/user/avatar/1a2b3c.png", "图片附件"));
        }

        @ParameterizedTest(name = "外站必须拒绝：{0}")
        @ValueSource(strings = {
                "http://evil.example.com/tracker.png",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://127.0.0.1:6379/x.png",
                "https://other-bucket.oss-cn-guangzhou.aliyuncs.com/user/avatar/1a2b3c.png",
                // ⚠️ 攻击者常见手法：把自家域名放在子域里骗过 contains 式判断
                "https://" + HOST + ".evil.com/x.png",
                "https://evil.com/?x=" + HOST
        })
        void rejectsForeignHost(String url) {
            assertThrows(ValidationException.class, () -> guard.validateHost(url, "图片附件"));
        }

        @Test
        @DisplayName("没有域名 / 空地址")
        void rejectsMalformed() {
            assertThrows(ValidationException.class, () -> guard.validateHost(null, "图片附件"));
            assertThrows(ValidationException.class, () -> guard.validateHost("  ", "图片附件"));
            assertThrows(ValidationException.class, () -> guard.validateHost("not a url", "图片附件"));
        }

        @Test
        @DisplayName("大小写不同也认（DNS 大小写不敏感）")
        void isCaseInsensitive() {
            assertDoesNotThrow(() -> guard.validateHost(
                    "https://NEXUS-AGENT-FILE.OSS-CN-GUANGZHOU.ALIYUNCS.COM/user/avatar/a.png", "图片附件"));
        }
    }

    @Nested
    @DisplayName("配置缺失")
    class Misconfigured {

        @Test
        @DisplayName("endpoint / bucket 没配 → 明确抛 IllegalStateException，而不是默默放行")
        void failsLoudWhenMisconfigured() {
            AliOssProperties empty = new AliOssProperties();
            OssUrlGuard broken = new OssUrlGuard(empty);
            // 静默放行等于把校验变成摆设，这里必须炸
            assertThrows(IllegalStateException.class, () -> broken.validateHost(
                    "https://" + HOST + "/user/avatar/a.png", "图片附件"));
        }
    }
}
