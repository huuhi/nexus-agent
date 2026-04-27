package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.em.LoginType;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.huzhijian.nexusagentweb.content.RedisContent.EMAIL_CODE_PREFIX;


/**
* @author windows
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-04-16 20:00:27
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final RedisUtils redisUtils;

    public UserServiceImpl(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    @Override
    public String login(UserLoginDTO loginDTO) {
//        校验邮箱是否注册
        String email = loginDTO.email();
        User user = query().eq("email", email).one();

        if (loginDTO.type()== LoginType.CODE){
//            验证码登录
            String code = redisUtils.get(EMAIL_CODE_PREFIX+email);
            if (code==null||!code.equals(loginDTO.code())) {
                throw new ValidationException("验证码错误/过期！");
            }
        }else if (loginDTO.type()==LoginType.PASSWORD){
            if (user==null){
                throw new ValidationException("邮箱/密码错误！");
            }
            String rawPassword = loginDTO.password();
            if (rawPassword == null) {
                throw new ValidationException("请输入密码！");
            }
            String encodedPassword = user.getPassword();
            if (encodedPassword==null){
                throw new ValidationException("未设置密码！请用验证码登录！");
            }

            if (!encoder.matches(rawPassword,encodedPassword)) {
                throw new ValidationException("邮箱/密码错误！");
            }
        }
//        走到这，说明登录成功了！
//        判断一下用户是否注册
        if (user==null){
//            自动注册
            return register(email,null);
        }
        return JwtUtil.createJWT(Map.of("user_id",user.getId()));
    }

    @Override
    public String register(UserRegisterDTO registerDTO) {
        String email = registerDTO.email();
        User user = query().eq("email",email ).one();
        if (user != null) {
            throw new ValidationException("用户已存在！");
        }
        if (!redisUtils.get(EMAIL_CODE_PREFIX+email).equals(registerDTO.code())) {
            throw new ValidationException("验证码错误/过期！");
        }
        return register(email,registerDTO.username());
    }

    @Override
    public void updateOrSetPassword(UserPasswordDTO passwordDTO) {
//        这里必须要验证当前的邮箱和当前登录用户是否为同一个人
        String email = passwordDTO.email();
        User user = query().eq("email", email)
                .one();
        if (user==null){
            throw new NotFoundException("用户不存在！");
        }
//        验证ID是否于当前登录用户ID一致
        if (!user.getId().equals(UserContextHolder.getUserId())){
            throw new ValidationException("邮箱错误！");
        }
        String code = redisUtils.get(EMAIL_CODE_PREFIX + email);
        if (code==null||!code.equals(passwordDTO.code())) {
            throw new ValidationException("验证码错误/过期！");
        }
        update().eq("email",email)
                .set("password",encoder.encode(passwordDTO.password()))
                .update();
    }

    public String register(String email,String name) {
//        随机一个用户名
        String username= name==null?"妖怪_"+RandomUtil.randomString(6):name;
//        TODO 头像 之后写一个头像集合，随机一个头像
        String url="https://nexus-agent-file.oss-cn-guangzhou.aliyuncs.com/user/avatar/76041334-9f55-41bb-b298-77da76572eab.jpg";

        User user = User.builder().email(email)
                .username(username)
                .avatarImg(url)
                .build();
        save(user);

        return JwtUtil.createJWT(Map.of("user_id",user.getId()));
    }




}




