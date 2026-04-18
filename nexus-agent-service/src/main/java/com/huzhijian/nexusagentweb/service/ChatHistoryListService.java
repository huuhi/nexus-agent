package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.constraints.NotBlank;

/**
* @author windows
* @description 针对表【chat_history_list】的数据库操作Service
* @createDate 2026-04-18 11:52:04
*/
public interface ChatHistoryListService extends IService<ChatHistoryList> {

    void createTitle(String sessionId, String message,String answer,Long userId);
}
