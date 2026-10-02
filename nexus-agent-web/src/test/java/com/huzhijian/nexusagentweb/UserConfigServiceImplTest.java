package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.UserConfigMapper;
import com.huzhijian.nexusagentweb.service.impl.UserConfigServiceImpl;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserConfigServiceImpl} 的纯单测。
 * <p>
 * 覆盖 2026-10-03 修的那个线上 NPE：
 * {@code saveOrUpdateMcpToken} 在 user_config 里还没有该用户记录时（用户先配 MCP、没配过 LLM Key）
 * 直接 {@code config.getSalt()} → {@code NullPointerException}。
 * 方法名叫 saveOrUpdate，实际只有 update 分支，save 分支压根没写。
 * <p>
 * ⚠️ 运行需要环境变量 {@code API_KEY_SECRET}（{@link EncryptorFactory} 类初始化时读）。
 * 已由 nexus-agent-web/pom.xml 的 surefire {@code environmentVariables} 提供，本地裸跑 mvn 也一样生效。
 */
@DisplayName("UserConfigServiceImpl —— MCP Token 保存（含首次设置的建行分支）")
class UserConfigServiceImplTest {

    private UserConfigMapper mapper;
    private RedisUtils redisUtils;
    private UserConfigServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(UserConfigMapper.class);
        redisUtils = mock(RedisUtils.class);
        service = new UserConfigServiceImpl(mapper, redisUtils);
        injectBaseMapper(service, mapper);
        UserContextHolder.saveId(1001L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    /**
     * MyBatis-Plus 的 {@code ServiceImpl#baseMapper} 是 protected 字段，
     * Spring 环境下由框架注入；纯单测里只能反射塞进去，否则 {@code query()} 一调就 NPE。
     */
    private static void injectBaseMapper(UserConfigServiceImpl service, UserConfigMapper mapper) throws Exception {
        Field field = ServiceImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(service, mapper);
    }

    @Test
    @DisplayName("user_config 无记录时：走新建分支，不能 NPE（线上就是这个场景炸的）")
    void saveMcpTokenWhenNoRowExists() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.saveOrUpdateMcpToken("ms-plain-token");

        ArgumentCaptor<UserConfig> captor = ArgumentCaptor.forClass(UserConfig.class);
        verify(mapper).save(captor.capture());
        verify(mapper, never()).updateMcpTokenById(any());

        UserConfig saved = captor.getValue();
        assertEquals(1001L, saved.getUserId());
        assertNotNull(saved.getSalt(), "新建时必须自带 salt，否则以后解密不出来");
        assertNotEquals("ms-plain-token", saved.getMcpToken(), "入库的必须是密文");
        assertEquals("ms-plain-token",
                EncryptorFactory.text(saved.getSalt()).decrypt(saved.getMcpToken()),
                "用同一个 salt 应该能解回明文");
        // llm_api_token 是 jsonb NOT NULL 列 —— 留 null 会让 ::jsonb 参数类型不确定，
        // 而且后面 getApiConfig() 读出来 .toString() 会 NPE，所以必须写成空数组
        assertEquals("[]", saved.getLlmApiToken());
    }

    @Test
    @DisplayName("已有记录时：只更新 mcp_token / salt，不碰 llm_api_token")
    void updateMcpTokenWhenRowExists() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(
                UserConfig.builder().userId(1001L).llmApiToken("[]").salt("0123456789abcdef").build());

        service.saveOrUpdateMcpToken("ms-plain-token");

        ArgumentCaptor<UserConfig> captor = ArgumentCaptor.forClass(UserConfig.class);
        verify(mapper).updateMcpTokenById(captor.capture());
        verify(mapper, never()).save(any());

        UserConfig updated = captor.getValue();
        assertEquals("0123456789abcdef", updated.getSalt(), "已有 salt 必须沿用，换掉会让旧密文解不开");
        assertEquals("ms-plain-token",
                EncryptorFactory.text(updated.getSalt()).decrypt(updated.getMcpToken()));
    }

    @Test
    @DisplayName("已有记录但 salt 为空（历史脏数据）：补生成 salt，不能抛异常")
    void regenerateSaltWhenBlank() {
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(
                UserConfig.builder().userId(1001L).llmApiToken("[]").salt(null).build());

        service.saveOrUpdateMcpToken("ms-plain-token");

        ArgumentCaptor<UserConfig> captor = ArgumentCaptor.forClass(UserConfig.class);
        verify(mapper).updateMcpTokenById(captor.capture());
        UserConfig updated = captor.getValue();
        assertNotNull(updated.getSalt());
        assertEquals("ms-plain-token",
                EncryptorFactory.text(updated.getSalt()).decrypt(updated.getMcpToken()));
    }

    @Test
    @DisplayName("未登录：抛未登录异常，且不写库")
    void rejectWhenNotLoggedIn() {
        UserContextHolder.removeUserId();

        assertThrows(UnauthorizedException.class, () -> service.saveOrUpdateMcpToken("x"));
        verify(mapper, never()).save(any());
        verify(mapper, never()).updateMcpTokenById(any());
    }

    @Test
    @DisplayName("getApiConfig：llm_api_token 为 null 时返回空列表，不能 NPE")
    void getApiConfigReturnsEmptyWhenTokenNull() {
        doReturn(UserConfig.builder().userId(1001L).llmApiToken(null).salt("0123456789abcdef").build())
                .when(redisUtils).queryWithPassThrough(anyString(), any(), any(), any(), anyLong(), any(TimeUnit.class));

        List<?> configs = service.getApiConfig();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }
}
