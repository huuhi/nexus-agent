package com.huzhijian.nexusagentweb.service;

import com.huzhijian.nexusagentweb.domain.ChatHistoryList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/30
 * 说明:
 */
@SpringBootTest
public class SortTest {
    @Autowired
    private ChatHistoryListService chatHistoryListService;

    @Test
    void getList()throws Exception{
        List<ChatHistoryList> list = chatHistoryListService.getList();
        for (ChatHistoryList chatHistoryList : list) {
            System.out.println(chatHistoryList.getUpdateTime());
        }
    }
}
