package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.User;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

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

    /**
     * 跨周期时把用量清零并写入新的周期起点（P2-8 遗留，见 {@code docs/sql/007}）。
     * <p>
     * 用**一条原子 UPDATE** 而不是「查 → 判断 → 写」，并且把判断条件写进 WHERE：
     * 并发的两个请求只会有一个命中，另一个的 WHERE 已不成立（天然幂等）。
     * <p>
     * ⚠️ 只对 {@code token_period <> 'NONE'} 的用户生效 —— 其余用户的
     * {@code token_used} 必须保持"只增不减"，否则等于悄悄改了 003 的语义。
     *
     * @return 受影响行数；{@code 0} 表示「不需要重置」或「用户不存在」
     */
    int resetQuotaPeriod(@Param("userId") Long userId, @Param("periodStart") LocalDateTime periodStart);

}
