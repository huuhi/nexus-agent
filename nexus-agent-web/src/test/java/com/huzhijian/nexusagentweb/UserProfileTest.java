package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.UserServiceImpl;
import com.huzhijian.nexusagentweb.utils.OssUrlGuard;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import com.huzhijian.nexusagentweb.vo.UserProfileVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户资料读写（2026-10-06 新增，配套头像上传 + 设置页表单化）。
 *
 * <p>存在的理由：原先前端只能<b>解码 JWT</b> 拿昵称与头像，那份数据有三个问题 ——
 * 没有 email、改了不同步（JWT 是登录那一刻的快照，有效期 7 天）、且是签名过的凭据不该当数据源。
 */
@DisplayName("用户资料 —— 读 / 改，以及头像 URL 的域名校验")
class UserProfileTest {

    private static final Long UID = 7L;
    private static final String OSS_URL = "https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/user/avatar/x.jpg";

    private UserServiceImpl service(UserMapper mapper, OssUrlGuard guard) {
        UserServiceImpl service = new UserServiceImpl(mock(RedisUtils.class), new AgentProperties(), guard);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private User existing() {
        return User.builder().id(UID).email("a@b.com").username("妖怪_ab")
                .avatarImg(OSS_URL).role("NORMAL").registerTime(new Date()).build();
    }

    /** 让 MP 的 getById 走 mock mapper */
    private UserMapper mapperWith(User user) {
        UserMapper mapper = mock(UserMapper.class);
        when(mapper.selectById(UID)).thenReturn(user);
        return mapper;
    }

    @Test
    @DisplayName("读资料：返回数据库当前值（不是 JWT 快照）")
    void getProfileReturnsCurrentValues() {
        UserProfileVO vo = service(mapperWith(existing()), mock(OssUrlGuard.class)).getProfile(UID);

        assertEquals("a@b.com", vo.getEmail());
        assertEquals("妖怪_ab", vo.getUsername());
        assertEquals(OSS_URL, vo.getAvatarImg());
        assertEquals("NORMAL", vo.getRole());
        assertEquals(UID, vo.getId());
    }

    @Test
    @DisplayName("未登录时读资料直接拒（不能把 userId 为 null 当成「查不到」）")
    void getProfileRequiresLogin() {
        UserServiceImpl service = service(mapperWith(existing()), mock(OssUrlGuard.class));
        assertThrows(UnauthorizedException.class, () -> service.getProfile(null));
    }

    @Test
    @DisplayName("改昵称：只 set 昵称，其余字段为 null —— 不能把头像/邮箱写成 null")
    void updateUsernameDoesNotWipeOtherFields() {
        UserMapper mapper = mapperWith(existing());
        service(mapper, mock(OssUrlGuard.class)).updateProfile(UID, "新昵称", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).updateById(captor.capture());
        User patch = captor.getValue();
        assertEquals("新昵称", patch.getUsername());
        assertEquals(null, patch.getAvatarImg(), "头像必须是 null（交给 MP 的「忽略 null 字段」语义，"
                + "而不是显式写一个空值把它清掉）");
        assertEquals(null, patch.getEmail());
    }

    @Test
    @DisplayName("换头像：校验 OSS 域名（防止把任意外链/追踪像素存进库）")
    void updateAvatarValidatesHost() {
        UserMapper mapper = mapperWith(existing());
        OssUrlGuard guard = mock(OssUrlGuard.class);
        service(mapper, guard).updateProfile(UID, null, OSS_URL);

        // 必须校验一次，且用的是「头像」这个用途名（出错时用户看得懂）
        verify(guard).validateHost(OSS_URL, "头像");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(OSS_URL, captor.getValue().getAvatarImg());
    }

    @Test
    @DisplayName("域名校验不通过时抛 ValidationException，且**不**写库")
    void illegalAvatarUrlIsRejected() {
        UserMapper mapper = mapperWith(existing());
        OssUrlGuard guard = mock(OssUrlGuard.class);
        org.mockito.Mockito.doThrow(new ValidationException("URL 域名不在允许范围内"))
                .when(guard).validateHost(any(), any());

        assertThrows(ValidationException.class,
                () -> service(mapper, guard).updateProfile(UID, null, "https://evil.example.com/x.png"));
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("昵称超长直接拒（前端有 @Size 兜一层，服务端也要有）")
    void usernameTooLongRejected() {
        UserMapper mapper = mapperWith(existing());
        String tooLong = "x".repeat(65);

        assertThrows(ValidationException.class,
                () -> service(mapper, mock(OssUrlGuard.class)).updateProfile(UID, tooLong, null));
        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("两个字段都不传：什么都不做且不报错（前端「保存」可能被误点）")
    void noChangeIsSilentNoop() {
        UserMapper mapper = mapperWith(existing());
        service(mapper, mock(OssUrlGuard.class)).updateProfile(UID, null, null);

        verify(mapper, never()).updateById(any());
        verify(mapper, never()).updateByPrimaryKeySelective(any());
    }

    @Test
    @DisplayName("只传空串昵称等同于不改（trim 后为空）")
    void blankUsernameIsNoop() {
        UserMapper mapper = mapperWith(existing());
        service(mapper, mock(OssUrlGuard.class)).updateProfile(UID, "   ", null);

        verify(mapper, never()).updateById(any());
    }

    @Test
    @DisplayName("昵称前后空格被 trim 掉")
    void usernameTrimmed() {
        UserMapper mapper = mapperWith(existing());
        service(mapper, mock(OssUrlGuard.class)).updateProfile(UID, "  新昵称  ", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("新昵称", captor.getValue().getUsername());
        assertTrue(captor.getValue().getUsername().equals(captor.getValue().getUsername().strip()));
    }
}