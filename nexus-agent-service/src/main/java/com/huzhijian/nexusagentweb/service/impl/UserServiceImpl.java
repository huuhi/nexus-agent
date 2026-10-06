package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.User;
import com.huzhijian.nexusagentweb.dto.UserLoginDTO;
import com.huzhijian.nexusagentweb.dto.UserPasswordDTO;
import com.huzhijian.nexusagentweb.dto.UserRegisterDTO;
import com.huzhijian.nexusagentweb.em.LoginType;
import com.huzhijian.nexusagentweb.em.QuotaPeriod;
import com.huzhijian.nexusagentweb.em.UserRole;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.mapper.UserMapper;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.UserService;
import com.huzhijian.nexusagentweb.utils.OssUrlGuard;
import com.huzhijian.nexusagentweb.vo.UserProfileVO;
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
    /** 换头像时校验 URL 必须是本项目 OSS —— 否则用户能把任意外链（含追踪像素）存进库 */
    private final OssUrlGuard ossUrlGuard;

    public UserServiceImpl(RedisUtils redisUtils, AgentProperties agentProperties,
                           OssUrlGuard ossUrlGuard) {
        this.redisUtils = redisUtils;
        this.agentProperties = agentProperties;
        this.ossUrlGuard = ossUrlGuard;
    }

    // ==================================================================
    //  用户资料（2026-10-06 新增：头像上传 + 设置页表单化）
    // ==================================================================

    @Override
    public UserProfileVO getProfile(Long userId) {
        requireLogin(userId);
        User user = getById(userId);
        if (user == null) {
            // 鉴权过了但库里没有这一行：属于数据不一致，明确报错比返回半截对象好排查
            throw new NotFoundException("用户不存在");
        }
        return UserProfileVO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .username(user.getUsername())
                .avatarImg(user.getAvatarImg())
                .role(user.getRole())
                .registerTime(user.getRegisterTime())
                .build();
    }

    @Override
    public void updateProfile(Long userId, String username, String avatarImg) {
        requireLogin(userId);
        // 只 set 要改的字段：MyBatis-Plus 的 updateById 默认忽略 null 字段，
        // 所以「只改昵称」不会把头像/邮箱/配额写成 null。
        // ⚠️ 刻意**不用**手写的 updateByPrimaryKeySelective：那套 XML 是历史遗留，
        //    而 users 表没有 jsonb 列，不存在「MP 更新会类型不匹配」的限制 —— 用 MP 更稳。
        User patch = User.builder().id(userId).build();
        boolean hasChange = false;

        if (username != null) {
            String trimmed = username.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > 64) {
                    throw new ValidationException("昵称最长 64 个字符");
                }
                patch.setUsername(trimmed);
                hasChange = true;
            }
        }
        if (avatarImg != null && !avatarImg.isBlank()) {
            // 🔴 必须是本项目 OSS 域名：否则用户能把任意外链存进来，
            //    页面每次渲染都等于给第三方递一次访问（追踪像素），头像位还能被用来钓鱼。
            ossUrlGuard.validateHost(avatarImg, "头像");
            patch.setAvatarImg(avatarImg);
            hasChange = true;
        }
        if (!hasChange) {
            // 前端「保存」在没改动时也可能被点到 —— 不该报错，也不该白跑一次 UPDATE
            return;
        }
        updateById(patch);
    }

    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
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

        // docs/sql/012：注册一律走「默认角色」（生产是 NORMAL）。
        // TEST / VIP 不由注册接口产出 —— 那两档是运营在库里改 users.role 授予的。
        UserRole role = defaultRole();
        User user = User.builder().email(email)
                .username(username)
                .avatarImg(url)
                .role(role.name())
//                P2-8：新用户的 token 额度。2026-10-06 起按**角色档位**写死，
//                而不是留 NULL 让校验时回落 —— 库里显式有值，运营查一次就能知道"这人是哪档"，
//                也不会出现"改了 role 但额度还是老值"的错觉（改档位必须同时改这两列）。
                .tokenQuota(role.dailyTokens())
                .tokenUsed(0L)
//                角色档位都是**按天**的口径（每天 100 万 / 1000 万），周期必须显式写 DAILY：
//                全局默认 period=NONE 是"累计不重置"，沿用会让"每天"变成"一辈子"。
                .tokenPeriod(QuotaPeriod.DAILY.name())
                .fileQuota(role.dailyFiles())
                .build();
        save(user);

        return JwtUtil.createJWT(Map.of("user_id",user.getId(),"image_url",user.getAvatarImg(),"user_name",user.getUsername()));
    }

    /**
     * 新注册用户的默认角色（{@code docs/sql/012}）。
     * <p>
     * 取 {@code nexus.agent.quota.default-role}；无法识别的值由 {@link UserRole#parse}
     * 回落到 {@code NORMAL} —— 配错配置最多是"档位偏保守"，不会配出一个额度离谱的档位。
     */
    private UserRole defaultRole() {
        return UserRole.parse(agentProperties.getQuota().getDefaultRole());
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




