package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.huzhijian.nexusagentweb.domain.SysFile;
import com.huzhijian.nexusagentweb.em.BizType;
import com.huzhijian.nexusagentweb.mapper.FileMapper;
import com.huzhijian.nexusagentweb.service.impl.FileServiceImpl;
import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件删除的回归用例（2026-10-05）。
 * <p>
 * <b>线上现象</b>：「文件与产物」面板（42 个文件 · 对话 13 · 产物 29）里，
 * <b>每一行点删除都弹「产物不存在或无权删除」</b>。
 * <p>
 * <b>根因</b>：面板是统一视图，列表里既有 AI 产物（{@code biz_type=ARTIFACT}）
 * 也有用户上传的对话附件（{@code biz_type=CHAT}），而删除按钮对所有行打的都是
 * {@code DELETE /api/artifact/{id}} —— 该端点原先硬加了 {@code biz_type=ARTIFACT} 条件，
 * 于是 13 个附件<b>永远命中不了</b>。
 * 归属校验本来就靠 {@code user_id}，bizType 不是安全边界，加它只会误伤。
 * <p>
 * 这里把「删除不限 biz_type」这个行为钉死，顺带盯住删除顺序：
 * 先删记录、再<b>尽力</b>删 OSS（OSS 失败不能反过来让删除失败）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件删除 —— 不限 biz_type（对话附件删不掉的回归用例）")
class FileServiceDeleteTest {

    private static final String OSS_URL = "https://bucket.oss-cn-guangzhou.aliyuncs.com/file/a.pdf";

    @Mock
    private AliOssUtil ossUtil;

    private FileMapper fileMapper;
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() throws Exception {
        fileMapper = mock(FileMapper.class);
        fileService = new FileServiceImpl(ossUtil);
        // ServiceImpl#baseMapper 是 protected 字段，纯单测里只能反射塞，
        // 否则 query() / removeById() 一调就 NPE
        Field field = ServiceImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(fileService, fileMapper);
        initTableInfo();
    }

    /**
     * 手动解析一次 {@link SysFile} 的表元数据。
     * <p>
     * ❗ 不这么做会报 {@code Cannot invoke "TableInfo.isWithLogicDelete()" because "tableInfo" is null}：
     * {@code ServiceImpl#removeById} 第一步就是 {@code TableInfoHelper.getTableInfo(entityClass)}
     * 判断是否逻辑删除，而 TableInfo 平时是 MyBatis 启动时扫描 mapper 顺带建的 —— 纯单测没有这个阶段。
     * 这是<b>测试环境</b>的问题，不是实现的问题，所以补在测试里。
     */
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
    @DisplayName("用户上传的对话附件（biz_type=CHAT）必须能删 —— 旧版硬限定 ARTIFACT 导致永远删不掉")
    void chatAttachmentCanBeDeleted() {
        when(fileMapper.selectOne(any())).thenReturn(file(9L, 1L, BizType.CHAT));
        // mapper 是 mock，deleteById 默认返回 0（= 没删到），不 stub 的话断言永远红
        when(fileMapper.deleteById(9L)).thenReturn(1);

        assertTrue(fileService.delete(9L, 1L),
                "对话附件也是当前用户自己的文件，删不掉就是 bug");
        verify(fileMapper).deleteById(9L);
        verify(ossUtil).deleteByUrl(OSS_URL);
    }

    @Test
    @DisplayName("AI 产物（biz_type=ARTIFACT）照常能删（老行为不能丢）")
    void artifactCanBeDeleted() {
        when(fileMapper.selectOne(any())).thenReturn(file(9L, 1L, BizType.ARTIFACT));
        when(fileMapper.deleteById(9L)).thenReturn(1);

        assertTrue(fileService.delete(9L, 1L));
        verify(fileMapper).deleteById(9L);
        verify(ossUtil).deleteByUrl(OSS_URL);
    }

    @Test
    @DisplayName("别人的文件：返回 false，且绝不删他的 OSS 对象")
    void otherUsersFileIsNotDeleted() {
        // 带 user_id 的查询命中不了（归属不符）
        when(fileMapper.selectOne(any())).thenReturn(null);
        // 只按 id 查得到 —— 用于落空诊断日志，证明这条记录确实存在、只是不属于当前用户
        when(fileMapper.selectById(9L)).thenReturn(file(9L, 2L, BizType.ARTIFACT));

        assertFalse(fileService.delete(9L, 1L));
        // 注意别写 deleteById(any())：BaseMapper 有 deleteById(Serializable) 和 deleteById(T)
        // 两个重载，泛型擦除后 any() 两者都匹配，javac 直接报「引用不明确」
        verify(fileMapper, never()).deleteById(9L);
        verify(ossUtil, never()).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("记录不存在（前端传错 id）：返回 false，不动 OSS")
    void missingFileReturnsFalse() {
        when(fileMapper.selectOne(any())).thenReturn(null);
        when(fileMapper.selectById(9L)).thenReturn(null);

        assertFalse(fileService.delete(9L, 1L));
        verify(ossUtil, never()).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("OSS 对象没清掉（尽力删除返回 false）：记录已经删了，不能反过来让删除失败")
    void ossFailureDoesNotFailDeletion() {
        when(fileMapper.selectOne(any())).thenReturn(file(9L, 1L, BizType.ARTIFACT));
        when(fileMapper.deleteById(9L)).thenReturn(1);
        when(ossUtil.deleteByUrl(OSS_URL)).thenReturn(false);

        assertTrue(fileService.delete(9L, 1L),
                "对象残留只是存储成本，报错反而会让用户以为没删掉");
        verify(fileMapper).deleteById(9L);
    }

    // 注：「deleteByUrl 自己必须吞掉异常、绝不往外抛」是 AliOssUtil 的契约，
    // 由 AliOssUtilDeleteByUrlTest 守住 —— 在这里 doThrow 打不到那一层（ossUtil 是 mock，
    // 绕过了真实实现），测出来的只是「mock 抛了异常」，没有意义。

    @Test
    @DisplayName("参数为 null：直接返回 false，不查库不删 OSS")
    void nullArgumentsAreRejected() {
        assertFalse(fileService.delete(null, 1L));
        assertFalse(fileService.delete(9L, null));
        verify(ossUtil, never()).deleteByUrl(anyString());
    }

    @Test
    @DisplayName("批量删除场景：同一批里 CHAT 与 ARTIFACT 混着删，各自独立生效")
    void mixedTypesAreDeletedIndependently() {
        when(fileMapper.selectOne(any()))
                .thenReturn(file(1L, 1L, BizType.CHAT))
                .thenReturn(file(2L, 1L, BizType.ARTIFACT));
        when(fileMapper.deleteById(1L)).thenReturn(1);
        when(fileMapper.deleteById(2L)).thenReturn(1);

        assertTrue(fileService.delete(1L, 1L));
        assertTrue(fileService.delete(2L, 1L));
        verify(ossUtil, org.mockito.Mockito.times(2)).deleteByUrl(OSS_URL);
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
