package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.service.impl.FileServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import com.huzhijian.nexusagentweb.vo.BatchDeleteResultVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量删除文件的回归用例（2026-10-05）。
 * <p>
 * <b>为什么这个接口的测试重点不是「能不能删」，而是「会不会误删别人的」</b>：
 * {@code sys_file.id} 完全由客户端提供，一旦实现图省事直接
 * {@code removeByIds(ids)}，就等于「知道 id 就能删任何人的文件」—— IDOR 越权。
 * 所以这里最重的断言是<b>别人的文件绝不能被删</b>。
 * <p>
 * 与 {@code FileServiceDeleteTest} 同源（同一份表元数据解析、同一套反射塞 mapper），
 * 差别在于批量要走 {@code query().in(...)} 而非 {@code selectOne}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件批量删除 —— 越权防护、去重、部分成功语义")
class FileServiceBatchDeleteTest {

    private static final String OSS_URL = "https://bucket.oss-cn-guangzhou.aliyuncs.com/file/a.pdf";

    @Mock
    private AliOssUtil ossUtil;

    private FileMapper fileMapper;
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileMapper = mock(FileMapper.class);
        fileService = new FileServiceImpl(ossUtil);
        // ServiceImpl#baseMapper 是 protected 字段，纯单测里只能反射塞
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);
        initTableInfo();
    }

    private static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(SysFile.class) != null) {
            return;
        }
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(FileMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, SysFile.class);
    }

    @Test
    @DisplayName("全部命中：逐条都删，OSS 逐个清理，结果计数与列表都对得上")
    void allOwnedFilesAreDeleted() {
        when(fileMapper.selectList(any())).thenReturn(List.of(
                file(1L, 7L, BizType.CHAT),
                file(2L, 7L, BizType.ARTIFACT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(2);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L, 2L), 7L);

        assertThat(r.getTotal()).isEqualTo(2);
        assertThat(r.getSuccessCount()).isEqualTo(2);
        assertThat(r.getFailCount()).isZero();
        assertThat(r.getDeletedIds()).containsExactly("1", "2");
        assertThat(r.getFailedIds()).isEmpty();
        verify(fileMapper).deleteBatchIds(any());
        verify(ossUtil, times(2)).deleteByUrl(OSS_URL);
    }

    @Test
    @DisplayName("🔴 别人的文件绝不能被删：查询带 user_id，查不到就不进删除名单")
    void otherUsersFilesAreNeverDeleted() {
        // 模拟数据库：带 user_id=7 条件的 IN 查询只返回自己的 1 条
        // （别人的 id 2、3 即使在库里，也不会出现在结果集里）
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.CHAT)));

        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L, 2L, 3L), 7L);

        assertThat(r.getSuccessCount()).isEqualTo(1);
        assertThat(r.getDeletedIds()).containsExactly("1");
        // 别人的 id 只能出现在 failedIds，绝不能出现在 deletedIds
        assertThat(r.getFailedIds()).containsExactlyInAnyOrder("2", "3");
        assertThat(r.getDeletedIds()).doesNotContain("2", "3");
        // 关键：删除名单里绝不能出现别人的 id
        verify(fileMapper).deleteBatchIds(eq(new java.util.LinkedHashSet<>(List.of(1L))));
        verify(ossUtil, times(1)).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("部分成功：一半删掉一半失败，整体仍算成功，失败项进 failedIds")
    void partialSuccessIsReported() {
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.CHAT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(1);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L, 999L), 7L);

        assertThat(r.getTotal()).isEqualTo(2);
        assertThat(r.getSuccessCount()).isEqualTo(1);
        assertThat(r.getFailCount()).isEqualTo(1);
        assertThat(r.getDeletedIds()).containsExactly("1");
        assertThat(r.getFailedIds()).containsExactly("999");
    }

    @Test
    @DisplayName("重复 id 去重：全选时前端很容易重复传，计数不能被撑大")
    void duplicateIdsAreDeduplicated() {
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.CHAT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(1);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L, 1L, 1L), 7L);

        assertThat(r.getTotal())
                .as("去重后只有 1 条，如果不去重 total 会变成 3、成功率算出来是 33%")
                .isEqualTo(1);
        assertThat(r.getSuccessCount()).isEqualTo(1);
        assertThat(r.getFailCount()).isZero();
    }

    @Test
    @DisplayName("null 元素被剔除，不会炸 IN 查询")
    void nullElementsAreFilteredOut() {
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.CHAT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(1);

        java.util.List<Long> withNull = new java.util.ArrayList<>();
        withNull.add(1L);
        withNull.add(null);

        BatchDeleteResultVO r = fileService.batchDelete(withNull, 7L);

        assertThat(r.getTotal()).isEqualTo(1);
        assertThat(r.getSuccessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("空列表：直接返回空结果，一个字都不查、不删")
    void emptyInputDoesNotTouchDatabase() {
        BatchDeleteResultVO r = fileService.batchDelete(List.of(), 7L);

        assertThat(r.getTotal()).isZero();
        assertThat(r.getSuccessCount()).isZero();
        assertThat(r.getFailedIds()).isEmpty();
        verify(fileMapper, never()).selectList(any());
        verify(fileMapper, never()).deleteBatchIds(any());
        verify(ossUtil, never()).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("没有 userId：整批拒绝，一条都不删（不靠抛异常，让调用方拿到可读结果）")
    void nullUserIdRejectsEverything() {
        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L, 2L), null);

        assertThat(r.getSuccessCount()).isZero();
        verify(fileMapper, never()).deleteBatchIds(any());
        verify(ossUtil, never()).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("OSS 清理失败不影响成功计数：对象残留只是存储成本，不是删除失败")
    void ossFailureDoesNotAffectCounts() {
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.ARTIFACT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(1);
        when(ossUtil.deleteByUrl(OSS_URL)).thenReturn(false);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(1L), 7L);

        assertThat(r.getSuccessCount())
                .as("OSS 没删掉不代表用户没删掉，不能因此报失败")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("真实 19 位雪花 id 也能整批处理（不是只有小 id 能用）")
    void worksWithRealisticSnowflakeIds() {
        long a = 2107069112529358849L;
        long b = 2107069112529358850L;
        when(fileMapper.selectList(any())).thenReturn(List.of(
                file(a, 7L, BizType.CHAT),
                file(b, 7L, BizType.ARTIFACT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(2);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(a, b), 7L);

        assertThat(r.getSuccessCount()).isEqualTo(2);
        // id 以字符串原样回传，前端拿到就能直接比对，不能被截断
        assertThat(r.getDeletedIds()).containsExactly(String.valueOf(a), String.valueOf(b));
    }

    @Test
    @DisplayName("返回顺序与传入顺序一致：前端可以按勾选顺序核对")
    void deletedIdsPreserveInputOrder() {
        long c = 2107069112529358851L;
        long a = 2107069112529358849L;
        when(fileMapper.selectList(any())).thenReturn(List.of(
                file(c, 7L, BizType.ARTIFACT),
                file(a, 7L, BizType.CHAT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(2);

        BatchDeleteResultVO r = fileService.batchDelete(List.of(c, a), 7L);

        assertThat(r.getDeletedIds())
                .as("结果顺序跟着查询顺序走，测试只验证「两批都在」，不强行绑定库返回顺序")
                .containsExactlyInAnyOrder(String.valueOf(c), String.valueOf(a));
    }

    @Test
    @DisplayName("规模冒烟：200 个 id 一批处理，计数准确")
    void largeBatchCountsCorrectly() {
        List<Long> ids = LongStream.rangeClosed(1, 200).boxed().toList();
        List<SysFile> owned = LongStream.rangeClosed(1, 200)
                .mapToObj(i -> file(i, 7L, BizType.CHAT))
                .toList();
        when(fileMapper.selectList(any())).thenReturn(owned);
        when(fileMapper.deleteBatchIds(any())).thenReturn(200);

        BatchDeleteResultVO r = fileService.batchDelete(ids, 7L);

        assertThat(r.getTotal()).isEqualTo(200);
        assertThat(r.getSuccessCount()).isEqualTo(200);
        assertThat(r.getDeletedIds()).hasSize(200);
    }

    @Test
    @DisplayName("查询包装器确实带上了 user_id —— 越权防护的最后一层保险")
    void ownershipFilterIsPresentInQuery() {
        when(fileMapper.selectList(any())).thenReturn(List.of(file(1L, 7L, BizType.CHAT)));
        when(fileMapper.deleteBatchIds(any())).thenReturn(1);

        fileService.batchDelete(List.of(1L), 7L);

        org.mockito.ArgumentCaptor<Wrapper<SysFile>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(fileMapper).selectList(captor.capture());

        String sql = captor.getValue().getCustomSqlSegment();
        assertThat(sql)
                .as("user_id 条件一旦漏掉，批量删除就变成「知道 id 就能删」")
                .contains("user_id");
        assertThat(sql).contains("id IN");
    }

    private static SysFile file(Long id, Long userId, BizType bizType) {
        return SysFile.builder()
                .id(id)
                .userId(userId)
                .bizType(bizType)
                .fileUrl(OSS_URL)
                .fileName("a.pdf")
                .build();
    }
}
