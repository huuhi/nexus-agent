package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.exception.NotFoundException;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.UserConfigMapper;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.huzhijian.nexusagentweb.content.RedisContent.CONFIG_KEY;
import static com.huzhijian.nexusagentweb.content.RedisContent.CONFIG_TTL;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Service实现
* @createDate 2026-04-26 20:27:21
*/
@Service
@Slf4j
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig>
    implements UserConfigService {
    private final UserConfigMapper userConfigMapper;
    private final RedisUtils redisUtils;

    public UserConfigServiceImpl(UserConfigMapper userConfigMapper, RedisUtils redisUtils) {
        this.userConfigMapper = userConfigMapper;
        this.redisUtils = redisUtils;
    }


    @Override
    public void saveOrUpdateAPIConfig(APIConfig apiConfig) {
//        API_KEY_SECRET
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        redisUtils.delete(CONFIG_KEY+userId);

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
            //第一个添加，设为默认
            apiConfig.setIsDefault(true);
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
//            用户可能先配了 MCP Token 才有这一行，那时 llm_api_token 是空数组而不是 null；
//            但历史脏数据里也可能是 null，这里兜住，避免 .toString() NPE。
        String rawToken = config.getLlmApiToken() == null ? EMPTY_API_TOKEN_JSON : config.getLlmApiToken().toString();
        List<APIConfig> apiConfigs = JSONUtil.toList(rawToken, APIConfig.class);

        if (id==null||id.isEmpty()){
//            说明是添加配置
            log.debug("添加新的配置：{}",apiConfig);
            apiConfig.setId(generateId);
            apiConfigs.add(apiConfig);
        }else{
            log.debug("更新配置");
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
    public UserConfig getUserConfig(Long userId) {
        return redisUtils.queryWithPassThrough(
                CONFIG_KEY,
                userId,
                UserConfig.class,
                this::getById,
                CONFIG_TTL,
                TimeUnit.DAYS);
    }

    /**
     * 新建 user_config 记录时的 {@code llm_api_token} 默认值。
     * <p>
     * 该列是 {@code jsonb NOT NULL DEFAULT '[]'}：
     * 1. 传 null 会让 mapper 里的 {@code #{llmApiToken}::jsonb} 变成参数类型不确定的 {@code NULL::jsonb}，
     *    PostgreSQL 直接报 {@code could not determine data type}；
     * 2. 留 null 的话 {@link #getApiConfig()} 读出来再 {@code .toString()} 会 NPE。
     * 所以"只配了 MCP、还没配 LLM"的用户也要写成空数组，而不是留空。
     */
    private static final String EMPTY_API_TOKEN_JSON = "[]";

    @Override
    public void saveOrUpdateMcpToken(String token) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        redisUtils.delete(CONFIG_KEY + userId);
        UserConfig config = getById(userId);
        if (config == null) {
//            首次设置：user_config 里还没有这个用户的行（比如他还没配过 LLM API Key）
            String salt = KeyGenerators.string().generateKey();
            String encrypt = EncryptorFactory.text(salt).encrypt(token);
            userConfigMapper.save(UserConfig.builder()
                    .userId(userId)
                    .llmApiToken(EMPTY_API_TOKEN_JSON)
                    .mcpToken(encrypt)
                    .salt(salt)
                    .build());
            return;
        }
        String salt = config.getSalt() == null || config.getSalt().isBlank()
                ? KeyGenerators.string().generateKey()
                : config.getSalt();
        String encrypt = EncryptorFactory.text(salt).encrypt(token);
        config.setMcpToken(encrypt);
        config.setSalt(salt);
//        不能用 MP 自带的 updateById：llm_api_token 是 jsonb 列，MP 会把 Java String 当 varchar 传进去，
//        PostgreSQL 报「column is of type jsonb but expression is of type character varying」。
//        这里只改 mcp_token / salt 两列，绕开 jsonb。
        userConfigMapper.updateMcpTokenById(config);
    }

    @Override
    public List<APIConfig> getApiConfig() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig config = getUserConfig(userId);
        if (config == null || config.getLlmApiToken() == null) {
            return List.of();
        }
        String json = config.getLlmApiToken().toString();
        List<APIConfig> configs = JSONUtil.toList(json, APIConfig.class);
        configs.forEach(key->{
            String apiKey = decryptKey(config.getSalt(), key.getAPIKey());
            key.setAPIKey(apiKey);
        });
        return configs;
    }



    private UserConfig getById(Long userId){
        log.debug("查询配置~");
        return query().eq("user_id", userId).one();
    }

    @Override
    public String getMCPConfig() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig config = query().eq("user_id",userId).one();
        if (config==null||config.getMcpToken()==null){
            throw new NotFoundException("未设置MCP的APIKEY");
        }
        String mcpToken = config.getMcpToken();
        return decryptKey(config.getSalt(), mcpToken);
    }

    private String decryptKey(String salt,String encryptKey){
        //            解密，并且只显示前面和末尾
        String apiKey = EncryptorFactory.text(salt).decrypt(encryptKey);
        // 换过 salt 或密文被改过时解密会拿到 null，下面 .length() 直接 NPE 变 500
        if (apiKey == null) {
            throw new ValidationException("MCP APIKEY 解密失败，请重新设置！");
        }
        int keepPrefix=2;
        int keepSuffix=4;
        if (apiKey.length()>keepSuffix+keepPrefix) {
            apiKey = apiKey.substring(0, 2) + "****" + apiKey.substring(apiKey.length() - 4);
        }else{
            apiKey="******";
        }
        return apiKey;
    }


}




