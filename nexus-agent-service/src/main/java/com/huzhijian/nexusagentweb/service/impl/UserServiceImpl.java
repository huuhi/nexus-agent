package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.em.LoginType;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.utils.JwtUtil;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final AgentProperties agentProperties;
    private final List<String> imageList=List.of("");

    public UserServiceImpl(RedisUtils redisUtils, AgentProperties agentProperties) {
        this.redisUtils = redisUtils;
        this.agentProperties = agentProperties;
    }

    @Override
    public String login(UserLoginDTO loginDTO) {
//        校验邮箱是否注册
        String email = loginDTO.email();
        User user = query().eq("email", email).one();

        if (loginDTO.type()== LoginType.CODE){
//            验证码登录
            validCode(email,loginDTO.code());
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
        return JwtUtil.createJWT(Map.of("user_id",user.getId(),"image_url",user.getAvatarImg(),"user_name",user.getUsername()));
    }

    @Override
    public String register(UserRegisterDTO registerDTO) {
        String email = registerDTO.email();
        User user = query().eq("email",email ).one();
        if (user != null) {
            throw new ValidationException("用户已存在！");
        }
        validCode(email,registerDTO.code());
        return register(email,registerDTO.username());
    }

    @Override
    public void updateOrSetPassword(UserPasswordDTO passwordDTO) {
//      重置/设置
        String email = passwordDTO.email();
        User user = query().eq("email", email)
                .one();
        if (user==null){
            throw new NotFoundException("用户不存在！");
        }
//        验证ID是否于当前登录用户ID一致
//        if (!user.getId().equals(UserContextHolder.getUserId())){
//            throw new ValidationException("邮箱错误！");
//        }
        validCode(email,passwordDTO.code());
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
//                P2-8：新用户默认 token 配额（未配置则存 null = 不限制，避免库里出现 0 这种歧义值）
                .tokenQuota(defaultTokenQuota())
                .tokenUsed(0L)
                .build();
        save(user);

        return JwtUtil.createJWT(Map.of("user_id",user.getId(),"image_url",user.getAvatarImg(),"user_name",user.getUsername()));
    }

    /**
     * 新注册用户的默认 token 配额。
     * <p>
     * {@code <=0} 时返回 null（= 不限制）。刻意不写 0：库里 0 与 NULL 虽然判定结果一样，
     * 但 NULL 明确表示"从未设过配额"，0 看起来像"配额是 0 却还能用"，排查时容易绕。
     */
    private Long defaultTokenQuota() {
        long quota = agentProperties.getQuota().getDefaultQuota();
        return quota > 0 ? quota : null;
    }


    /**
     * 校验邮箱验证码。
     * <p>
     * <b>2026-10-04 修复：验证码一次性，用后即删。</b>
     * 原来这里只读不删，而 Redis 里的验证码 TTL 是 5 分钟 ——
     * 同一份码在 5 分钟内能无限次重复使用。而 {@code PUT /api/user/password}
     * 是**免鉴权**路径（忘密码场景），于是任何拿到一份验证码的人都能在 5 分钟内
     * 反复改掉**任意已知邮箱**的密码 → 账号接管。
     * <p>
     * 顺序刻意是「先比对再删除」：若先删再比对，用户输错一次就得重新收邮件，
     * 体验很差。比对失败时不删（码还有效，用户可以重试）。
     */
    private void validCode(String email,String userCode){
        String key = EMAIL_CODE_PREFIX + email;
        String code = redisUtils.get(key);
        if (code==null||!code.equals(userCode)) {
            throw new ValidationException("验证码错误/过期！");
        }
        // 校验通过 → 立即失效，杜绝同一份码被重复使用
        redisUtils.delete(key);
    }


}




