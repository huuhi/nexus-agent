package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AliOssProperties;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@link AliOssUtil#deleteByUrl} 的契约（2026-10-05 抽出该方法时补）。
 * <p>
 * 这个方法被两处共用：{@code ArtifactServiceImpl#delete}（删产物）与
 * {@code FileServiceImpl#delete}（删用户文件）。两处的调用顺序都是
 * <b>「先删数据库记录，再尽力清理对象」</b> —— 也就是说调用它时记录已经没了，
 * 对象残留只是存储成本，<b>它抛异常就等于让用户以为没删掉</b>。
 * <p>
 * 所以这里要钉死两条：① 解析不出对象名就跳过，绝不碰 OSS；② OSS 抛什么都吞掉，
 * 只返回 {@code false}。
 * <p>
 * 用 {@code spy} 而不是 {@code mock}：要跑真实的 {@code deleteByUrl} 逻辑，
 * 只把最后一步 {@code deleteObject}（会真的去连 OSS）打桩掉。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AliOssUtil#deleteByUrl —— 尽力删除，绝不把异常甩回调用方")
class AliOssUtilDeleteByUrlTest {

    /** 带 query 参数 + 中文空格被 percent-encoding 过 —— 上传时就是这么存的 */
    private static final String URL =
            "https://bucket.oss-cn-guangzhou.aliyuncs.com/file/%E6%8A%A5%E5%91%8A%202026.pdf";
    private static final String OBJECT = "file/报告 2026.pdf";

    private AliOssUtil util;

    @BeforeEach
    void setUp() {
        util = spy(new AliOssUtil(new AliOssProperties()));
    }

    @Test
    @DisplayName("对象名要从 URL 反推：去掉 host、query/fragment，并做 UTF-8 解码")
    void objectNameIsDecodedAndQueryStripped() {
        assertEquals(OBJECT, AliOssUtil.objectNameOf(URL));
        assertEquals(OBJECT, AliOssUtil.objectNameOf(URL + "?x-oss-signature=abc#frag"),
                "带签名的临时链接同样要能反推出对象名");
    }

    @Test
    @DisplayName("URL 只有域名没有路径（或本身就是空）：返回 null，不删任何东西")
    void urlWithoutPathYieldsNull() {
        assertNull(AliOssUtil.objectNameOf("https://bucket.oss-cn-guangzhou.aliyuncs.com"));
        assertNull(AliOssUtil.objectNameOf("https://bucket.oss-cn-guangzhou.aliyuncs.com/"));
        assertNull(AliOssUtil.objectNameOf(null));
        assertNull(AliOssUtil.objectNameOf("   "));
    }

    @Test
    @DisplayName("正常路径：真的调了 deleteObject，并返回 true")
    void deletesObjectAndReturnsTrue() throws Exception {
        doNothing().when(util).deleteObject(OBJECT);

        assertTrue(util.deleteByUrl(URL));
        verify(util).deleteObject(OBJECT);
    }

    @Test
    @DisplayName("OSS 抛异常（凭证失效等）：吞掉并返回 false，绝不冒泡")
    void swallowsOssFailure() throws Exception {
        doThrow(new ValidationException("删除对象失败！凭证无效")).when(util).deleteObject(OBJECT);

        assertFalse(util.deleteByUrl(URL),
                "记录已经删了，这里抛异常只会让用户以为没删掉");
        verify(util).deleteObject(OBJECT);
    }

    @Test
    @DisplayName("解析不出对象名：直接跳过，绝不碰 OSS")
    void skipsWhenObjectNameUnresolvable() throws Exception {
        assertFalse(util.deleteByUrl("https://bucket.oss-cn-guangzhou.aliyuncs.com"));
        assertFalse(util.deleteByUrl(null));
        verify(util, never()).deleteObject(anyString());
    }
}
