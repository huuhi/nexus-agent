package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.UserConfig;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Service
* @createDate 2026-04-26 20:27:21
*/
public interface UserConfigService extends IService<UserConfig> {

    void saveAPIConfig(APIConfig config);
}
