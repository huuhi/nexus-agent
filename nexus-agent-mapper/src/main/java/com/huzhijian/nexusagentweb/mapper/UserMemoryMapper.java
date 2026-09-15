package com.huzhijian.nexusagentweb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.dto.SearchMemoryRequest;
import com.huzhijian.nexusagentweb.vo.MemorySearchResult;

import java.util.List;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Mapper
* @createDate 2026-05-10 21:29:23
* @Entity com.huzhijian.nexusagentweb.domain.UserMemory
*/
public interface UserMemoryMapper extends BaseMapper<UserMemory> {
    List<MemorySearchResult> search(SearchMemoryRequest request);
}




