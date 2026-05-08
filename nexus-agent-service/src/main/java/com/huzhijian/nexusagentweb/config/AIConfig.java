package com.huzhijian.nexusagentweb.config;

import com.huzhijian.nexusagentweb.service.ChatAssistant;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/8
 * 说明: 聊天模型 默认配置 实用于超长文本
 */
@Configuration
public class AIConfig {
    private final String key=System.getenv("MOONSHOT");
    @Bean
    public ChatAssistant chatAssistant() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl("https://api.moonshot.cn/v1")
                .modelName("moonshot-v1-128k").build();
        return AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .build();
    }
}
