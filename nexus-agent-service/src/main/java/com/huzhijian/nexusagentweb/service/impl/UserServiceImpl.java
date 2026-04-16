package com.huzhijian.nexusagentweb.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
* @author windows
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-04-16 20:00:27
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

}




