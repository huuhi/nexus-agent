package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.domain.UserLongMemory;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.mapper.UserConfigMapper;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.huzhijian.nexusagentweb.content.RedisContent.LONG_MEMORY_GROUP_KEY;
import static com.huzhijian.nexusagentweb.content.RedisContent.LONG_MEMORY_STREAM;

/**
* @author windows
* @description 针对表【user_config(用户SKILL关系模型)】的数据库操作Service实现
* @createDate 2026-04-26 20:27:21
*/
@Service
@Slf4j
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig>
    implements UserConfigService, DisposableBean {
    private volatile boolean isRunning = true;
    private final UserConfigMapper userConfigMapper;
    private final RedisUtils redisUtils;
    private final ChatMemoryServiceImpl chatMemoryService;
    private final ChatModel model;
    private static final ExecutorService COMPLETE_NODE_EXECUTOR =  new ThreadPoolExecutor(
            5,
            10,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    @PostConstruct
    public void init() {
        COMPLETE_NODE_EXECUTOR.execute(new handleLongMemory());
    }
    @Override
    public void destroy() {
        isRunning = false;
        log.info("Stopping LEARN_PATH_EXECUTOR...");
        COMPLETE_NODE_EXECUTOR.shutdown(); // 停止接收新任务
        try {
            // 等待线程池终止（包括处理中的任务）
            if (!COMPLETE_NODE_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                COMPLETE_NODE_EXECUTOR.shutdownNow(); // 强制终止
            }
        } catch (InterruptedException e) {
            COMPLETE_NODE_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt(); // 恢复中断状态
        }
        log.info("LEARN_PATH_EXECUTOR stopped.");
    }
    public UserConfigServiceImpl(UserConfigMapper userConfigMapper, RedisUtils redisUtils, ChatMemoryServiceImpl chatMemoryService) {
        this.userConfigMapper = userConfigMapper;
        this.redisUtils = redisUtils;
        this.chatMemoryService = chatMemoryService;
        this.model = getModel();
    }



    private class handleLongMemory implements Runnable{
        @Override
        public void run() {
            while (isRunning){
                List<MapRecord<String, Object, Object>> msg = redisUtils.getMsg(LONG_MEMORY_STREAM, LONG_MEMORY_GROUP_KEY, "c1");
                if (msg==null||msg.size()==0){
                    continue;
                }
                MapRecord<String, Object, Object> record = msg.get(0);
                handleMemory(record);
                redisUtils.ackAndDelMsg(LONG_MEMORY_STREAM,LONG_MEMORY_GROUP_KEY,record.getId());
            }
        }

        private void handleMemory(MapRecord<String, Object, Object> record) {
            Object sessionId = record.getValue().get("sessionId");
            if (sessionId==null){return;}
            List<ChatHistory> memories = chatMemoryService.getByMemoryId(sessionId);
            List<ChatHistory> filterMemories = memories.stream().filter(m -> m.getType() != MessageType.TOOL_EXECUTION_RESULT).toList();
//            获取最新10条消息
            int size = filterMemories.size();
            int start = Math.max(0, size - 10);
            String chatHistory = filterMemories.subList(start, size).stream().map(m -> m.getContent().toString()).toList().toString();
            Long userId = filterMemories.getFirst().getUserId();
            String oldMemory = query().eq("user_id", userId)
                    .one().getUserDefault().toString();
            log.debug("发送的旧记忆：{}，聊天记录：{}",oldMemory,chatHistory);
            String message = String.format("之前的记忆:%s,最新的聊天%s", oldMemory, chatHistory);
            ChatResponse chatResponse = model.chat(UserMessage.from(message));
            String arrayJson = chatResponse.aiMessage().text();
            List<UserLongMemory> userLongMemories = JSONUtil.toList(arrayJson, UserLongMemory.class);
            log.debug("AI返回的记录：{}",userLongMemories);
            String jsonStr = JSONUtil.toJsonStr(userLongMemories);
            userConfigMapper.saveLongMemory(jsonStr,userId);
        }
    }

    @Override
    public void saveOrUpdateAPIConfig(APIConfig apiConfig) {
//        API_KEY_SECRET
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }

        String generateId= RandomUtil.randomString(10)+RandomUtil.randomNumber();
//        加密KEY
        String apiKey = apiConfig.getAPIKey();
        String salt = KeyGenerators.string().generateKey();

//      根据用户ID获取配置
        UserConfig config = getById(userId);
        if (config==null){
//        添加 配置
//            添加api配置
            apiConfig.setId(generateId);
            String encryptKey = EncryptorFactory.text(salt).encrypt(apiKey);
            apiConfig.setAPIKey(encryptKey);

            String jsonConfig = JSONUtil.toJsonStr(List.of(apiConfig));
            UserConfig userConfig = UserConfig.builder().userId(userId).llmApiToken(jsonConfig).salt(salt).build();
            userConfigMapper.save(userConfig);
            return;
        }
//        如果说不是新增用户配置，说明有salt，使用用户专有的进行加密。
        salt=config.getSalt();
        String encryptKey = EncryptorFactory.text(salt).encrypt(apiKey);
        apiConfig.setAPIKey(encryptKey);
//        更新
        String id = apiConfig.getId();
        List<APIConfig> apiConfigs= JSONUtil.toList(config.getLlmApiToken().toString(), APIConfig.class);

        if (id==null){
//            说明是添加配置
            apiConfig.setId(generateId);
            apiConfigs.add(apiConfig);
        }else{
            apiConfigs=apiConfigs.stream().map(c -> {
                if (c.getId().equals(id)) {
                    return apiConfig;
                }
//            如果当前配置为默认，那么其他配置设置成非默认，只能存在一个默认配置。
                if (apiConfig.getIsDefault()){
                    c.setIsDefault(false);
                }
                return c;
            }).toList();
        }
        String jsonConfigs = JSONUtil.toJsonStr(apiConfigs);
        config.setLlmApiToken(jsonConfigs);
        userConfigMapper.updateAPIconfigById(config);
    }

    @Override
    public void saveOrUpdateMcpToken(String token) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new UnauthorizedException("未登录！");
        }
        UserConfig config = getById(userId);
        String salt=config.getSalt()==null?KeyGenerators.string().generateKey():config.getSalt();
        String encrypt = EncryptorFactory.text(salt).encrypt(token);
        config.setMcpToken(encrypt);
        config.setSalt(salt);
        updateById(config);
    }

    private ChatModel getModel(){
        JsonObjectSchema memoryItemSchema = JsonObjectSchema.builder()
                .addStringProperty("id", "记忆ID")
                .addStringProperty("content", "记忆内容")
                .addStringProperty("category", "记忆的分类")
                .required("id", "content", "category")
                .additionalProperties(false)
                .build();
        JsonObjectSchema rootSchema = JsonObjectSchema.builder()
                .addProperty("memories", JsonArraySchema.builder()
                        .description("所有记忆的列表")
                        .items(memoryItemSchema)
                        .build())
                .required("memories")  // 标记 memories 为必需
                .additionalProperties(false)  // 禁止在根对象中添加其他字段
                .build();
        ResponseFormat responseFormat = ResponseFormat
                .builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("memory")
                        .rootElement(rootSchema).build())
                .build();
        return OpenAiChatModel.builder()
                .responseFormat(responseFormat)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .apiKey(System.getenv("MOONSHOT"))
                .baseUrl("https://api.moonshot.cn/v1")
                .modelName("moonshot-v1-128k").build();
    }

}




