package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.vo.UserProfileVO;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;

/**
* @author windows
* @description 针对表【user(用户表)】的数据库操作Service
* @createDate 2026-04-16 20:00:27
*/
public interface UserService extends IService<User> {

    String login(UserLoginDTO loginDTO);

    String register(UserRegisterDTO registerDTO);

    void updateOrSetPassword(UserPasswordDTO passwordDTO);

    /**
     * 取当前用户资料（2026-10-06 新增）。
     * <p>
     * 供「设置页表单化」用 —— 原先前端只能解码 JWT 拿昵称与头像，
     * 拿不到 email，而且那份是<b>登录时的快照</b>，改了昵称 7 天内都不同步。
     *
     * @return 用户资料；userId 为 null 时返回 null（不该发生，鉴权拦得住）
     */
    UserProfileVO getProfile(Long userId);

    /**
     * 修改当前用户资料（2026-10-06 新增）。
     * <p>
     * 只允许改昵称与头像；email 与 role 是只读的（前者是登录凭据，后者由运营授予）。
     * 两者都传 null 时<b>什么都不做且不报错</b>（前端「保存」在无改动时也可能被点到）。
     *
     * @param username    新昵称，null 表示不改
     * @param avatarImg   新头像 URL，null 表示不改；<b>非 null 时校验必须是本项目 OSS 域名</b>
     * @throws UnauthorizedException 未登录
     * @throws ValidationException   昵称超长 / 头像 URL 非法
     */
    void updateProfile(Long userId, String username, String avatarImg);
}
