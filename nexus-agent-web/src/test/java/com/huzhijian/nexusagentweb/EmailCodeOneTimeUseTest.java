package com.huzhijian.nexusagentweb;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.UserServiceImpl;
import com.huzhijian.nexusagentweb.utils.OssUrlGuard;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link UserServiceImpl} 邮箱验证码的回归测试。
 * <p>
 * <b>对应的漏洞（P0）</b>：{@code validCode} 原先<b>只读不删</b>，而 Redis 里验证码
 * TTL 是 5 分钟 —— 同一份码在 5 分钟内能<b>无限次重复使用</b>。
 * 而 {@code PUT /api/user/password} 是<b>免鉴权</b>路径（忘密码场景），
 * 于是任何拿到一份验证码的人都能在 5 分钟内反复改掉<b>任意已知邮箱</b>的密码 → 账号接管。
 * <p>
 * <b>修复</b>：校验通过即 {@code redisUtils.delete(key)}，验证码一次性。
 * 顺序刻意是「先比对再删除」：先删再比对的话，用户输错一次就得重新收邮件。
 *
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/10/4
 */
@DisplayName("UserServiceImpl —— 邮箱验证码一次性（防账号接管）")
class EmailCodeOneTimeUseTest {

    private static final String EMAIL = "victim@example.com";
    private static final String CODE = "123456";
    /** {@code RedisContent.EMAIL_CODE_PREFIX}，这里写死是为了让测试在断言里暴露它的值 */
    private static final String CODE_KEY = "code:" + EMAIL;

    private UserMapper mapper;
    private RedisUtils redisUtils;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = mock(UserMapper.class);
        redisUtils = mock(RedisUtils.class);
        // 2026-10-06：构造多了 OssUrlGuard（换头像时校验 OSS 域名），本用例不碰头像
        service = new UserServiceImpl(redisUtils, new AgentProperties(), mock(OssUrlGuard.class));
        injectBaseMapper(service, mapper);
    }

    private static void injectBaseMapper(UserServiceImpl service, UserMapper mapper) throws Exception {
        Field field = ServiceImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(service, mapper);
    }

    /** 让 query().eq("email", ...).one() 返回一个已存在的用户 */
    private void givenExistingUser() {
        doReturn(User.builder().id(7L).email(EMAIL).username("victim").password("$2a$10$x").build())
                .when(mapper).selectOne(any());
    }

    /**
     * 注册路径会 {@code save(user)} 之后用 {@code user.getId()} 去签 JWT。
     * MyBatis-Plus 的 insert 会把自增主键回填到实体上，Mockito 不会 ——
     * 不 stub 的话 {@code Map.of("user_id", null, ...)} 直接抛 NPE，
     * 报错点在 Map 而不是业务逻辑上，排查起来很误导。
     */
    private void givenInsertFillsGeneratedId() {
        doAnswer(inv -> {
            inv.getArgument(0, User.class).setId(4242L);
            return 1;
        }).when(mapper).insert(any());
    }

    // ------------------------------------------------------------------ 核心回归

    @Test
    @DisplayName("🔴 P0：同一份验证码不能被重放（免鉴权改密 + 无限重放 = 账号接管）")
    void codeCannotBeReplayed() {
        givenExistingUser();
        // 模拟「Redis 里有码」；第一次校验通过后应被删除
        doReturn(CODE).doReturn(null)
                .when(redisUtils).get(anyString());

        UserPasswordDTO dto = new UserPasswordDTO(EMAIL, CODE, "NewPass123!");

        // 第一次：正常使用
        service.updateOrSetPassword(dto);
        verify(redisUtils, times(1)).delete(anyString());

        // 第二次：攻击者拿着同一份码再来 → Redis 里已经没了 → 必须失败
        assertThrows(ValidationException.class, () -> service.updateOrSetPassword(dto));
    }

    @Test
    @DisplayName("校验通过后必须真的删掉 Redis 里的码（而不是只读）")
    void deletesCodeAfterSuccessfulValidation() {
        givenExistingUser();
        doReturn(CODE).when(redisUtils).get(anyString());

        UserPasswordDTO dto = new UserPasswordDTO(EMAIL, CODE, "NewPass123!");

        service.updateOrSetPassword(dto);

        verify(redisUtils).delete(CODE_KEY);
    }

    @Test
    @DisplayName("注册路径同样一次性（否则可反复注册/覆盖）")
    void registerAlsoConsumesCode() {
        // 用户不存在 → 直接进注册分支
        doReturn(null).when(mapper).selectOne(any());
        givenInsertFillsGeneratedId();
        doReturn(CODE).doReturn(null).when(redisUtils).get(anyString());

        UserRegisterDTO dto = new UserRegisterDTO(EMAIL, "someone", CODE);

        // 第一次成功
        service.register(dto);
        verify(redisUtils).delete(anyString());

        // 第二次同一份码 → Redis 已空
        assertThrows(ValidationException.class, () -> service.register(dto));
    }

    // ------------------------------------------------------------------ 体验不回归

    @Test
    @DisplayName("输错码时**不**删 Redis 里的码（用户输错一次不该就要重新收邮件）")
    void wrongCodeDoesNotConsumeIt() {
        givenExistingUser();
        doReturn(CODE).when(redisUtils).get(anyString());

        UserPasswordDTO dto = new UserPasswordDTO(EMAIL, "000000", "NewPass123!");

        assertThrows(ValidationException.class, () -> service.updateOrSetPassword(dto));

        // 关键：比对失败不能删码，否则用户输错一次就被锁死，得重新收邮件
        verify(redisUtils, never()).delete(anyString());
    }

    @Test
    @DisplayName("Redis 里压根没码（已过期）→ 拒绝")
    void missingCodeIsRejected() {
        givenExistingUser();
        doReturn(null).when(redisUtils).get(anyString());

        UserPasswordDTO dto = new UserPasswordDTO(EMAIL, CODE, "NewPass123!");

        assertThrows(ValidationException.class, () -> service.updateOrSetPassword(dto));
        verify(redisUtils, never()).delete(anyString());
    }
}
