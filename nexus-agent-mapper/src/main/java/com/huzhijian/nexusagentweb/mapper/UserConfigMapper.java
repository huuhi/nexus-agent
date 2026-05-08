package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Mapper
* @createDate 2026-04-24 20:27:07
* @Entity com.huzhijian.nexusagentweb.domain.UserConfig
*/
public interface UserConfigMapper extends BaseMapper<UserConfig> {


    void updateAPIconfigById(UserConfig config);

    void save(UserConfig userConfig);

    @Update("update user_cofig set user_default=#{jsonStr}::jsonb where user_id=#{userId}")
    void saveLongMemory(String jsonStr, Long userId);

    @Update("update user_cofig set user_default=user_default||#{jsonStr}::jsonb where user_id=#{userId}")
    void updateUserMemory(String jsonStr, Long userId);

    @Select("select user_default from user_confgi where user_id=#{userId}")
    Object queryLongMemory(Long userId);
}




