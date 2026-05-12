package com.huzhijian.nexusagentweb.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.dto.SearchMemoryRequest;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Service
* @createDate 2026-05-10 21:29:23
*/
public interface UserMemoryService extends IService<UserMemory> {
     void saveMemory(UserMemory userMemory);

     String searchMemory(SearchMemoryRequest request);
}
