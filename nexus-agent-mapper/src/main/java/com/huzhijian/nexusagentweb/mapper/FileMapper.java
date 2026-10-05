package com.huzhijian.nexusagentweb.mapper;

import com.huzhijian.nexusagentweb.domain.SysFile;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
* @author windows
* @description 针对表【file】的数据库操作Mapper
* @createDate 2026-04-16 20:02:47
* @Entity com.huzhijian.nexusagentweb.domain.File
*/
public interface FileMapper extends BaseMapper<SysFile> {

    /**
     * 统计某个用户从 {@code since} 起落库的文件 + 产物条数（{@code docs/sql/012} 的文件配额用）。
     * <p>
     * 只数 {@code create_time >= since}：配额是**按天**的，
     * 而 create_time 本身带时区、天然按天滚动，不需要像 token 那样做惰性重置。
     * <p>
     * ⚠️ 刻意在 SQL 层做 COUNT，不要把整天的记录拉到 Java 里数 ——
     * 用户文件一多就是几百行白捞一遍。
     *
     * @param userId 用户 id
     * @param since  计数起点（传当天 00:00）
     * @return 条数；查不到返回 0（SQL 的 COUNT 不会返回 null）
     */
    long countSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}




