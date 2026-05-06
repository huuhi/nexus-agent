package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.UserConfigMapper;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;

import java.util.List;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Service实现
* @createDate 2026-04-26 20:27:21
*/
@Service
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig>
    implements UserConfigService{

    private final UserConfigMapper userConfigMapper;

    public UserConfigServiceImpl(UserConfigMapper userConfigMapper) {
        this.userConfigMapper = userConfigMapper;
    }

    @Override
    public void saveOrUpdateAPIConfig(APIConfig apiConfig) {
//        API_KEY_SECRET
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }

        String generateId= RandomUtil.randomString(10)+RandomUtil.randomNumber();
//        加密KEY
        String apiKey = apiConfig.getAPIKey();
        String salt = KeyGenerators.string().generateKey();

//      根据用户ID获取配置
        UserConfig config = getById(userId);
        if (config==null){
//        添加 配置
//            添加api配置
            apiConfig.setId(generateId);
            String encryptKey = EncryptorFactory.text(salt).encrypt(apiKey);
            apiConfig.setAPIKey(encryptKey);

            String jsonConfig = JSONUtil.toJsonStr(List.of(apiConfig));
            UserConfig userConfig = UserConfig.builder().userId(userId).llmApiToken(jsonConfig).salt(salt).build();
            userConfigMapper.save(userConfig);
            return;
        }
//        如果说不是新增用户配置，说明有salt，使用用户专有的进行加密。
        salt=config.getSalt();
        String encryptKey = EncryptorFactory.text(salt).encrypt(apiKey);
        apiConfig.setAPIKey(encryptKey);
//        更新
        String id = apiConfig.getId();
        List<APIConfig> apiConfigs= JSONUtil.toList(config.getLlmApiToken().toString(), APIConfig.class);

        if (id==null){
//            说明是添加配置
            apiConfig.setId(generateId);
            apiConfigs.add(apiConfig);
        }else{
            apiConfigs=apiConfigs.stream().map(c -> {
                if (c.getId().equals(id)) {
                    return apiConfig;
                }
//            如果当前配置为默认，那么其他配置设置成非默认，只能存在一个默认配置。
                if (apiConfig.getIsDefault()){
                    c.setIsDefault(false);
                }
                return c;
            }).toList();
        }
        String jsonConfigs = JSONUtil.toJsonStr(apiConfigs);
        config.setLlmApiToken(jsonConfigs);
        userConfigMapper.updateAPIconfigById(config);
    }

    @Override
    public void saveOrUpdateMcpToken(String token) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig config = getById(userId);
        String salt=config.getSalt()==null?KeyGenerators.string().generateKey():config.getSalt();
        String encrypt = EncryptorFactory.text(salt).encrypt(token);
        config.setMcpToken(encrypt);
        config.setSalt(salt);
        updateById(config);
    }
}




