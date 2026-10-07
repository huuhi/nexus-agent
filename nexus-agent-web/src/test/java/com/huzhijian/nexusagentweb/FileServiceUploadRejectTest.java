package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.em.UploadFailCode;
import com.huzhijian.nexusagentweb.em.UploadStatus;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.impl.FileServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import com.huzhijian.nexusagentweb.vo.KnowledgeFileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 上传「明确拒绝」的单测（2026-10-07，与 fronted 对齐后的契约）。
 * <p>
 * <b>原来是什么样</b>：`FileServiceImpl#uploadFile` 里类型不支持是
 * {@code continue} —— 文件既不入库也不报错，返回列表里<b>直接没有它</b>。
 * 前端拿不到任何失败信号，用户看到的只有「传完了但列表里没这个东西」，
 * 也就是用户反馈的「什么文件都能传，很离谱」的真正来源。
 * <p>
 * <b>现在</b>（fronted 拍板选 A）：逐条标记失败落进返回列表，一条坏文件不毁掉整批；
 * 并且带 {@code failCode} 让前端能机器区分失败类型（他明确要求
 * 「必须能区分『类型不支持』与『超出配额』」）。
 * <p>
 * ⚠️ <b>这些用例的价值在于「反向验证」</b>：把实现改回 {@code continue}，
 * 前两条会立刻变红（返回列表空、OSS 没被调用过）。
 */
@DisplayName("上传拒绝语义 —— 类型不支持要报错，不能静默消失")
class FileServiceUploadRejectTest {

    private AliOssUtil ossUtil;
    private QuotaService quotaService;
    private MockedStatic<UserContextHolder> userHolder;
    private MockedStatic<com.huzhijian.nexusagentweb.utils.OssUrlGuard> ignored;

    @BeforeEach
    void setUp() {
        ossUtil = mock(AliOssUtil.class);
        quotaService = mock(QuotaService.class);
        userHolder = mockStatic(UserContextHolder.class);
        userHolder.when(UserContextHolder::getUserId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        userHolder.close();
    }

    private FileServiceImpl newService(AgentProperties props) {
        FileServiceImpl service = new FileServiceImpl(ossUtil, quotaService, props);
        // saveBatch 需要 Mapper，这里不关心入库，用 spy 把它短路掉
        FileServiceImpl spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(true).when(spy).saveBatch(any());
        return spy;
    }

    private static MultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    @Test
    @DisplayName("类型不支持：不再静默跳过，返回列表里必须有一条 FAILED 记录")
    void unsupportedTypeIsReportedNotSilentlyDropped() throws Exception {
        when(ossUtil.uploadDocument(any(), anyString(), anyLong())).thenReturn("https://oss/a.exe");

        List<KnowledgeFileVO> result = newService(new AgentProperties())
                .uploadFile(new MultipartFile[]{file("virus.exe", new byte[]{1, 2, 3})}, BizType.CHAT);

        assertEquals(1, result.size(),
                "不支持的类型必须在返回列表里留下一条记录 —— 原来 continue 掉的话这里是 0，"
                        + "用户看到的就是「传完了但列表里没这个东西」");
        KnowledgeFileVO vo = result.get(0);
        assertEquals(UploadStatus.FAILED, vo.getUploadStatus());
        assertEquals(UploadFailCode.UNSUPPORTED_TYPE, vo.getFailCode());
        assertNotNull(vo.getFailReason(), "failReason 要给人看的原因，不能为空");
//        关键：根本不该往 OSS 传
        verifyNoInteractions(ossUtil);
    }

    @Test
    @DisplayName("混合批次：好文件照传、坏文件只标记自己失败（A 方案：不毁整批）")
    void badFileDoesNotRuinTheWholeBatch() throws Exception {
        when(ossUtil.uploadDocument(any(), anyString(), anyLong())).thenReturn("https://oss/ok.pdf");

        List<KnowledgeFileVO> result = newService(new AgentProperties()).uploadFile(
                new MultipartFile[]{
                        file("good.pdf", new byte[]{1}),
                        file("bad.exe", new byte[]{2}),
                        file("another.pdf", new byte[]{3})},
                BizType.CHAT);

        assertEquals(3, result.size(), "三个都要在列表里，一个都不能凭空消失");
        assertEquals(UploadStatus.SUCCESS, result.get(0).getUploadStatus());
        assertEquals(UploadFailCode.UNSUPPORTED_TYPE, result.get(1).getFailCode());
        assertEquals(UploadStatus.SUCCESS, result.get(2).getUploadStatus());
    }

    @Test
    @DisplayName("失败码要能区分「类型不支持」与「上传失败」—— 前端靠它分支，不是靠文案")
    void failCodeDistinguishesReasons() throws Exception {
        when(ossUtil.uploadDocument(any(), anyString(), anyLong()))
                .thenThrow(new ValidationException("凭证无效"));

        List<KnowledgeFileVO> result = newService(new AgentProperties())
                .uploadFile(new MultipartFile[]{file("doc.pdf", new byte[]{1})}, BizType.CHAT);

        assertEquals(UploadFailCode.UPLOAD_FAILED, result.get(0).getFailCode(),
                "OSS 失败与类型不支持必须给出不同的码 —— fronted 要求「必须能区分」");
    }

    @Test
    @DisplayName("个数超限：整批拒绝，且一个都不往 OSS 传")
    void tooManyFilesRejected() {
        AgentProperties props = new AgentProperties();
        int max = props.getUpload().getMaxCount();
        MultipartFile[] tooMany = new MultipartFile[max + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = file("f" + i + ".pdf", new byte[]{1});
        }

        ValidationException ex = assertThrows(ValidationException.class,
                () -> newService(props).uploadFile(tooMany, BizType.CHAT));

        assertEquals(true, ex.getMessage().contains(String.valueOf(max)),
                "报错要带上限数值，前端不用自己再写一遍数字：" + ex.getMessage());
        verifyNoInteractions(ossUtil);
        verify(quotaService, org.mockito.Mockito.never()).assertWithinFileQuota(anyLong());
    }

    @Test
    @DisplayName("默认上限是 10（与 fronted 拍板一致）")
    void defaultMaxCountIsTen() {
        assertEquals(10, new AgentProperties().getUpload().getMaxCount(),
                "fronted 拍板「单次最多 10 个」，改这个值要先同步前端");
    }
}
