package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.mapper.UserConfigMapper;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import org.springframework.stereotype.Service;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Service实现
* @createDate 2026-04-26 20:27:21
*/
@Service
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig>
    implements UserConfigService{

    @Override
    public void saveAPIConfig(APIConfig config) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        String jsonStr = JSONUtil.toJsonStr(config);
        save(UserConfig.builder().userId(userId).llmApiToken(jsonStr).build());
    }
}




