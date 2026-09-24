package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.User;
import org.apache.ibatis.annotations.Param;

/**
* @author windows
* @description 针对表【user(用户表)】的数据库操作Mapper
* @createDate 2026-04-16 20:02:07
* @Entity com.huzhijian.nexusagentweb.domain.User
*/
public interface UserMapper extends BaseMapper<User> {

    int deleteByPrimaryKey(Long id);

    int insert(User record);

    int insertSelective(User record);

    User selectByPrimaryKey(Long id);

    int updateByPrimaryKeySelective(User record);

    int updateByPrimaryKey(User record);

    /**
     * 累加 token 用量（P2-8）。实现是与 UserMapper.xml 里对应的**原子 UPDATE**，
     * 不是「查出来 → 加 → 写回」（后者在并发对话下会丢更新）。
     *
     * @return 受影响行数；0 表示用户不存在
     */
    int addTokenUsage(@Param("userId") Long userId, @Param("delta") long delta);

}
