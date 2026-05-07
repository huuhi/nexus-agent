package com.huzhijian.nexusagentweb.factory;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.context.ChatContext;
import com.huzhijian.nexusagentweb.context.UserConfigContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.ChatDTO;
import com.huzhijian.nexusagentweb.dto.ModelDTO;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.tools.BoxTool;
import com.huzhijian.nexusagentweb.tools.LogTool;
import com.huzhijian.nexusagentweb.tools.RagTool;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.HashedMap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/24
 * 说明:
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatContextFactory {
    private final StreamingChatModel defaultModel;
    private final PgChatMemoryStore chatMemoryStore;
    private final RagTool ragTool;
    private final McpInformationService mcpInformationService;
    private final BoxTool boxTool;
    private final LogTool logTool;


    public ChatContext create(ChatDTO chatDTO,Long userId){
        StreamingChatModel  model=createModel(chatDTO.model());
        String temp=chatDTO.sessionId();
//        是否为新的对话，如果是，创建新的会话ID，并且
        boolean isNewSession=temp==null||temp.isEmpty();
        String sessionId =isNewSession? UUID.randomUUID().toString():temp;
        McpToolProvider mcp = mcpInformationService.getMcp(chatDTO.MCPs(),userId);

        AiServices<ChatAssistant> builder = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(model)
                .tools(boxTool,logTool)
                .chatMemoryProvider(memoryId -> TokenWindowChatMemory
                        .builder()
                        .maxTokens(32000,new OpenAiTokenCountEstimator("gpt-4o"))
                        .chatMemoryStore(chatMemoryStore)
                        .id(sessionId)
                        .build());

        if (chatDTO.enableRag()){
            builder.tools(ragTool);
        }
        if (mcp!=null){
            builder.toolProvider(mcp);
        }
        ChatAssistant chatAssistant = builder.build();
        return ChatContext.builder().chatAssistant(chatAssistant)
                .sessionId(sessionId)
                .isNewSession(isNewSession)
                .build();
    }

    private StreamingChatModel createModel(ModelDTO modelDTO) {
        UserConfig userConfig = UserConfigContextHolder.getUserConfig();

        if (userConfig!=null&&modelDTO!=null){
//            构造模型
            String configJson = userConfig.getLlmApiToken().toString();
            List<APIConfig> apiConfigs = JSONUtil.toList(configJson, APIConfig.class);
            APIConfig apiConfig = apiConfigs.stream().filter(config -> {
//                如果ID不为空也不为null，那么优先根据id寻找配置，如果为null，那么使用默认配置
                if (modelDTO.id() != null && !modelDTO.id().isEmpty()) {
                    return config.getId().equals(modelDTO.id());
                }
                return config.getIsDefault();
            }).findFirst().orElse(null);
            if (apiConfig==null || !apiConfig.getModel().contains(modelDTO.modelName())) return defaultModel;
            String secretApiKey = apiConfig.getAPIKey();
            String apiKey = EncryptorFactory.text(userConfig.getSalt()).decrypt(secretApiKey);
            Map<String, Object> extraBody = new HashedMap<>();
            if (modelDTO.isThinking()){
                extraBody.put("thinking", Map.of("type", "enabled"));
                extraBody.put("enable_thinking", true);
            }
            extraBody.put("enable_search", true);
//          加个customParameters配置,控制是否开启思考
            return OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiConfig.getBaseUrl())
                    .modelName(modelDTO.modelName())
                    .returnThinking(true)
                    .sendThinking(true)
//                    目前这个配置只针对deepseek
                    .customParameters(extraBody)
                    .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                    .build();
        }
        return defaultModel;
    }
}
