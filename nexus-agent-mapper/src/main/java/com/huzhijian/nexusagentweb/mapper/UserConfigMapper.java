package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.UserConfig;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Mapper
* @createDate 2026-04-24 20:27:07
* @Entity com.huzhijian.nexusagentweb.domain.UserConfig
*/
public interface UserConfigMapper extends BaseMapper<UserConfig> {


    void updateAPIconfigById(UserConfig config);

    /**
     * 只更新 mcp_token 与 salt，不碰 llm_api_token。
     * <p>
     * 不能改用 MyBatis-Plus 自带的 {@code updateById}：llm_api_token 是 jsonb 列，
     * MP 会把 Java String 当 varchar 传，PostgreSQL 报类型不匹配。
     */
    void updateMcpTokenById(UserConfig config);

    void save(UserConfig userConfig);
}




