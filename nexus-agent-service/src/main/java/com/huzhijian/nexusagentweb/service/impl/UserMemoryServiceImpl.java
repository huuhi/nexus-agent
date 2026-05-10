package com.huzhijian.nexusagentweb.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huzhijian.nexusagentweb.domain.ChatHistory;
import com.huzhijian.nexusagentweb.domain.Memories;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.em.MessageType;
import com.huzhijian.nexusagentweb.mapper.UserMemoryMapper;
import com.huzhijian.nexusagentweb.service.UserMemoryService;
import com.huzhijian.nexusagentweb.utils.RedisUtils;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.huzhijian.nexusagentweb.content.ModelSystemContent.GET_MEMORY;
import static com.huzhijian.nexusagentweb.content.RedisContent.LONG_MEMORY_GROUP_KEY;
import static com.huzhijian.nexusagentweb.content.RedisContent.LONG_MEMORY_STREAM;

/**
* @author windows
* @description 针对表【user_memory(用户长期记忆)】的数据库操作Service实现
* @createDate 2026-05-10 21:29:23
*/
@Service
@Slf4j
public class UserMemoryServiceImpl extends ServiceImpl<UserMemoryMapper, UserMemory>
    implements UserMemoryService, DisposableBean {
    private volatile boolean isRunning = true;
    private final RedisUtils redisUtils;
    private final ChatMemoryServiceImpl chatMemoryService;
    private final ChatModel model;
    private final EmbeddingModel embeddingModel;
    private static final ExecutorService COMPLETE_NODE_EXECUTOR =  new ThreadPoolExecutor(
            5,
            10,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public UserMemoryServiceImpl(RedisUtils redisUtils, ChatMemoryServiceImpl chatMemoryService, ChatModel model, EmbeddingModel embeddingModel) {
        this.redisUtils = redisUtils;
        this.chatMemoryService = chatMemoryService;
        this.model = getModel();
        this.embeddingModel = embeddingModel;
    }

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



    @Transactional
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

        @Transactional
        public void handleMemory(MapRecord<String, Object, Object> record) {
            Object sessionId = record.getValue().get("sessionId");
            if (sessionId==null){return;}
            List<ChatHistory> memories = chatMemoryService.getByMemoryId(sessionId);
            List<ChatHistory> filterMemories = memories.stream().filter(m -> m.getType() != MessageType.TOOL_EXECUTION_RESULT).toList();
//            获取最新10条消息
            int size = filterMemories.size();
            int start = Math.max(0, size - 10);
            String chatHistory = filterMemories.subList(start, size).stream().map(m -> m.getContent().toString()).toList().toString();
            Long userId = filterMemories.getFirst().getUserId();

            List<String> memory = query().eq("user_id", userId)
                    .list().stream().map(m -> m.getCategory() + "_" + m.getContent()).toList();
            String memoryString = memory.toString();


            log.debug("发送的旧记忆：{}，聊天记录：{}",memoryString,chatHistory);
            String message = String.format("之前的记忆:%s,最新的聊天%s", memoryString, chatHistory);
            SystemMessage systemMessage = SystemMessage.from(GET_MEMORY);


            ChatResponse chatResponse = model.chat(systemMessage,UserMessage.from(message));

            String arrayJson = chatResponse.aiMessage().text();
            String cleanJson = cleanJson(arrayJson);
            Memories newMemories = JSONUtil.toBean(cleanJson, Memories.class);

            log.debug("AI返回的记录：{}",newMemories);
//          先处理删除
            List<String> delete = newMemories.getDelete();
            removeBatchByIds(delete);
//            如果是更新和添加都需要向量化内容
            List<UserMemory> add = newMemories.getAdd();
            add.forEach(m->{
                Embedding embedding = embeddingText(m.getContent());
                float[] vector = embedding.vector();
                m.setEmbedding(vector);
            });
            List<UserMemory> update = newMemories.getUpdate();
            update.forEach(m->{
                Embedding embedding = embeddingText(m.getContent());
                float[] vector = embedding.vector();
                m.setEmbedding(vector);
            });
//            理论上会报错的
            updateBatchById(update);
            saveBatch(add);
        }
    }

    public Embedding embeddingText(String text){
        if (text==null){return null;}
        return embeddingModel.embed(text).content();
    }
    private String cleanJson(String jsonStr){
        return jsonStr
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
    }

    private ChatModel getModel(){
        JsonObjectSchema addSchema = JsonObjectSchema.builder()
                .addStringProperty("id", "记忆ID")
                .addStringProperty("content", "记忆内容")
                .addStringProperty("category", "记忆的分类")
                .required("content", "category")
                .additionalProperties(false)
                .build();

        JsonObjectSchema updateSchema = JsonObjectSchema.builder()
                .addStringProperty("id", "记忆ID")
                .addStringProperty("content", "记忆内容")
                .addStringProperty("category", "记忆的分类")
                .required("id","content", "category")
                .additionalProperties(false)
                .build();
        JsonStringSchema stringSchema = JsonStringSchema.builder()
                .description("需要删除的记忆ID").build();
        JsonObjectSchema rootSchema = JsonObjectSchema.builder()
                .addProperty("update", JsonArraySchema.builder()
                        .description("更新的记忆(严格返回ID)")
                        .items(updateSchema)
                        .build())
                .addProperty("add",JsonArraySchema.builder()
                        .description("新增的记忆(不需要返回ID)")
                        .items(addSchema).build())
                .addProperty("delete",JsonArraySchema.builder()
                        .description("需要删除的记忆ID列表")
                        .items(stringSchema).build())
                .required("update","add","delete")  // 标记 memories 为必需
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
                .temperature(0.0)
                .baseUrl("https://api.moonshot.cn/v1")
                .modelName("moonshot-v1-128k").build();
    }
}




