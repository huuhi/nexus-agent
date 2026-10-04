package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.McpInformation;
import com.huzhijian.nexusagentweb.dto.McpServerItemDTO;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.McpInformationMapper;
import com.huzhijian.nexusagentweb.mcp.McpClientRegistry;
import com.huzhijian.nexusagentweb.service.impl.McpInformationServiceImpl;
import com.huzhijian.nexusagentweb.service.impl.UserConfigServiceImpl;
import com.huzhijian.nexusagentweb.utils.HttpUtils;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link McpInformationServiceImpl} 的纯单测。
 * <p>
 * 覆盖 2026-10-04 修的那条线上 500：添加 MCP 工具时报
 * {@code ERROR: null value in column "header" of relation "mcp_information" violates not-null constraint}。
 * <p>
 * <b>触发路径（务必记住，否则这个测试看着像无病呻吟）</b>：
 * 前端点「从服务商预置列表添加」，列表来自 {@code GET /api/mcp/service}，
 * 返回的是 {@code McpServerItemVO} —— <b>它没有 header 字段</b>（header 是用户凭据，
 * 不该出现在列表接口里）。前端把这坨数据原样回传给 {@code POST /api/mcp} 时，
 * {@code McpServerItemDTO.header} 就是 {@code null}。
 * 原来的 {@code JSONUtil.toJsonStr(null)} 在 hutool 里返回的是 {@code null}（不是字符串 "null"），
 * 于是写成 {@code NULL::jsonb}，撞上 {@code header jsonb NOT NULL}。
 * <p>
 * 另外三个坑也在这里盯：
 * <ul>
 *   <li>{@code available} 是 {@code boolean NOT NULL}，DTO 不传时不能写 null</li>
 *   <li>请求头值里的换行 = header 注入，必须在入库前拒绝</li>
 *   <li>库里有 {@code str_id IS NULL} 的历史行时，{@code Collectors.toMap} 会 NPE</li>
 * </ul>
 */
@DisplayName("McpInformationServiceImpl —— 登记 MCP 服务（header 空值 / 注入 / 幂等）")
class McpInformationServiceImplTest {

    private static final Long USER_ID = 1001L;
    private static final String SAFE_URL = "https://mcp.example.com/sse";

    private McpInformationMapper mapper;
    private McpClientRegistry registry;
    private UrlGuard urlGuard;
    private McpInformationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(McpInformationMapper.class);
        registry = mock(McpClientRegistry.class);
        urlGuard = mock(UrlGuard.class);          // 不做真实 DNS 解析，纯单测不该出网
        service = new McpInformationServiceImpl(
                mock(HttpUtils.class), mapper, mock(UserConfigServiceImpl.class), registry, urlGuard);
        injectBaseMapper(service, mapper);
        UserContextHolder.saveId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    /**
     * {@code ServiceImpl#baseMapper} 是 protected 字段，Spring 下由框架注入；
     * 纯单测里必须反射塞进去，否则 {@code query()} 一调就 NPE。
     * <p>注意 {@code query()} 走的是 {@code baseMapper.selectList(wrapper)}，
     * 所以 mock {@code selectList(any()) 即可让「已存在的记录」为空。
     */
    private static void injectBaseMapper(McpInformationServiceImpl service, McpInformationMapper mapper)
            throws Exception {
        Field field = ServiceImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(service, mapper);
    }

    private McpServerItemDTO dto(Map<String, Object> header) {
        return new McpServerItemDTO(null, "@MrCare/mcp_tool", SAFE_URL,
                "一个极简的天气查询工具", "天气查询工具", null, "streamable_http", header, null);
    }

    @Test
    @DisplayName("header 为 null（从服务商预置列表一键添加的真实场景）：写 {} 而不是 NULL，不违反 NOT NULL")
    void nullHeaderBecomesEmptyJsonObject() {
        when(mapper.selectList(any())).thenReturn(List.of());

        service.saveMcp(List.of(dto(null)));

        ArgumentCaptor<List<McpInformation>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveBatch(captor.capture());

        McpInformation saved = captor.getValue().get(0);
        assertEquals("{}", saved.getHeader(),
                "header 是 jsonb NOT NULL 列，写 null 会直接 500（线上就是这个错）");
        assertEquals(USER_ID, saved.getUserId());
    }

    @Test
    @DisplayName("header 为空 Map：同样归一化成 {}")
    void emptyHeaderMapBecomesEmptyJsonObject() {
        when(mapper.selectList(any())).thenReturn(List.of());

        service.saveMcp(List.of(dto(Map.of())));

        ArgumentCaptor<List<McpInformation>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveBatch(captor.capture());
        assertEquals("{}", captor.getValue().get(0).getHeader());
    }

    @Test
    @DisplayName("available 不传：必须补 true，不能把 null 写进 boolean NOT NULL 列")
    void nullAvailableBecomesTrue() {
        when(mapper.selectList(any())).thenReturn(List.of());

        service.saveMcp(List.of(dto(Map.of("Authorization", "Bearer x"))));

        ArgumentCaptor<List<McpInformation>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveBatch(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().get(0).getAvailable());
    }

    @Test
    @DisplayName("header 正常时：原样序列化成 JSON 文本入库")
    void headerIsSerializedAsJson() {
        when(mapper.selectList(any())).thenReturn(List.of());

        service.saveMcp(List.of(dto(new java.util.LinkedHashMap<>(Map.of("X-Api-Key", "k-123")))));

        ArgumentCaptor<List<McpInformation>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveBatch(captor.capture());
        assertEquals("{\"X-Api-Key\":\"k-123\"}", captor.getValue().get(0).getHeader());
    }

    @Test
    @DisplayName("header 值含换行：拒绝入库（换行能注入第二个请求头）")
    void rejectHeaderValueWithNewline() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(ValidationException.class,
                () -> service.saveMcp(List.of(dto(Map.of("X-Test", "a\r\nX-Injected: evil")))));
        verify(mapper, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("header 名含非法字符（空格/冒号）：拒绝入库")
    void rejectIllegalHeaderName() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(ValidationException.class,
                () -> service.saveMcp(List.of(dto(Map.of("Bad Header", "v")))));
        verify(mapper, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("header 值是对象/数组：拒绝入库（拼不成合法 HTTP 头值）")
    void rejectNonStringHeaderValue() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThrows(ValidationException.class,
                () -> service.saveMcp(List.of(dto(Map.of("X-Count", 123)))));
        verify(mapper, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("URL 指向内网：在入库前就被 UrlGuard 拦下，不写库")
    void rejectPrivateUrlBeforeInsert() {
        when(mapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalArgumentException("不能指向内网"))
                .when(urlGuard).validate(anyString(), anyString());

        assertThrows(IllegalArgumentException.class, () -> service.saveMcp(List.of(dto(null))));
        verify(mapper, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("库里存在同 strId 的记录：走更新分支，不新增（并作废客户端缓存）")
    void updateWhenStrIdAlreadyExists() {
        when(mapper.selectList(any())).thenReturn(List.of(
                McpInformation.builder().id(7L).strId("@MrCare/mcp_tool").userId(USER_ID).build()));

        service.saveMcp(List.of(dto(null)));

        verify(mapper, never()).saveBatch(anyList());
        ArgumentCaptor<McpInformation> captor = ArgumentCaptor.forClass(McpInformation.class);
        verify(mapper).updateMCP(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        verify(registry).evict(7L);
    }

    @Test
    @DisplayName("库里有 str_id IS NULL 的历史行：不能因 Collectors.toMap 的 null key 而 NPE")
    void tolerateLegacyNullStrIdRows() {
        when(mapper.selectList(any())).thenReturn(List.of(
                McpInformation.builder().id(1L).strId(null).userId(USER_ID).build(),
                McpInformation.builder().id(2L).strId("").userId(USER_ID).build()));

        service.saveMcp(List.of(dto(null)));

        // 空 strId 匹配不上任何已有行 → 走新增
        ArgumentCaptor<List<McpInformation>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).saveBatch(captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    @DisplayName("未登录：抛未登录异常，且不写库")
    void rejectWhenNotLoggedIn() {
        UserContextHolder.removeUserId();

        assertThrows(UnauthorizedException.class, () -> service.saveMcp(List.of(dto(null))));
        verify(mapper, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("空列表：直接返回，不查库不写库（否则 foreach 生成非法 SQL）")
    void emptyListIsNoop() {
        service.saveMcp(List.of());

        verify(mapper, never()).saveBatch(anyList());
        verify(mapper, never()).updateMCP(any());
    }

    @Test
    @DisplayName("parseHeader：null/空白/非法 JSON 都退化成空 Map，不抛异常（脏数据不该让功能整体不可用）")
    void parseHeaderIsForgiving() {
        assertTrue(McpInformationServiceImpl.parseHeader(null).isEmpty());
        assertTrue(McpInformationServiceImpl.parseHeader("  ").isEmpty());
        assertTrue(McpInformationServiceImpl.parseHeader("not-json{").isEmpty());
        assertEquals(Map.of("Authorization", "Bearer x"),
                McpInformationServiceImpl.parseHeader("{\"Authorization\":\"Bearer x\"}"));
    }
}
