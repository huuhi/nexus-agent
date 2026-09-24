package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * OSS URL 反解 objectName 的单元测试（P2-10 删除产物时用到）。
 * <p>
 * 这段逻辑错了的后果很具体：objectName 对不上 → OSS 里的文件删不掉（而且**不会报错**，
 * 因为删除不存在的对象是幂等成功的），存储会悄悄泄漏。所以必须测。
 */
class OssObjectNameTest {

    @Test
    @DisplayName("标准 URL：取出对象路径")
    void extractsPlainObjectName() {
        String url = "https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/user/1/artifact/2026-09-24/report.xlsx";

        assertEquals("user/1/artifact/2026-09-24/report.xlsx", AliOssUtil.objectNameOf(url));
    }

    @Test
    @DisplayName("percent-encoded 的中文文件名要解码回原样（否则删不掉对象）")
    void decodesPercentEncodedName() {
        // Python 侧上传时对 URL 做了 quote(safe='/')
        String url = "https://bucket.oss-cn-guangzhou.aliyuncs.com/user/1/artifact/2026-09-24/%E5%AD%A3%E5%BA%A6%E6%8A%A5%E8%A1%A8.xlsx";

        assertEquals("user/1/artifact/2026-09-24/季度报表.xlsx", AliOssUtil.objectNameOf(url));
    }

    @Test
    @DisplayName("带 query / fragment 时只取对象路径")
    void stripsQueryAndFragment() {
        String withQuery = "https://bucket.oss-cn-guangzhou.aliyuncs.com/user/1/artifact/2026-09-24/a.png?x-oss-process=style/thumb";
        String withFragment = "https://bucket.oss-cn-guangzhou.aliyuncs.com/user/1/artifact/2026-09-24/a.png#frag";

        assertEquals("user/1/artifact/2026-09-24/a.png", AliOssUtil.objectNameOf(withQuery));
        assertEquals("user/1/artifact/2026-09-24/a.png", AliOssUtil.objectNameOf(withFragment));
    }

    @Test
    @DisplayName("没有路径的 URL 返回 null（调用方据此跳过对象删除）")
    void returnsNullWithoutPath() {
        assertNull(AliOssUtil.objectNameOf("https://bucket.oss-cn-guangzhou.aliyuncs.com"));
        assertNull(AliOssUtil.objectNameOf("https://bucket.oss-cn-guangzhou.aliyuncs.com/"));
    }

    @Test
    @DisplayName("空值返回 null，不抛异常")
    void handlesNullAndBlank() {
        assertNull(AliOssUtil.objectNameOf(null));
        assertNull(AliOssUtil.objectNameOf(""));
        assertNull(AliOssUtil.objectNameOf("   "));
    }

    @Test
    @DisplayName("非法编码不抛异常（返回原串兜底）")
    void illegalEncodingDoesNotThrow() {
        String url = "https://bucket.oss-cn-guangzhou.aliyuncs.com/user/1/artifact/a%ZZ.png";

        // 解码失败时退化为原串：至少不让调用方因为一个坏 URL 崩掉
        assertEquals("user/1/artifact/a%ZZ.png", AliOssUtil.objectNameOf(url));
    }
}
