package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.User;
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
}
