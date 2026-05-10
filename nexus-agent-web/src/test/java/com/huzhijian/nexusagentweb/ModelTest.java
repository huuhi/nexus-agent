package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.domain.Memories;
import com.huzhijian.nexusagentweb.domain.UserMemory;
import com.huzhijian.nexusagentweb.mapper.UserMemoryMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static com.huzhijian.nexusagentweb.content.ModelSystemContent.GET_MEMORY;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/9
 * 说明:
 */
@SpringBootTest
public class ModelTest {

    @Resource
    private  EmbeddingModel embeddingModel;
    @Resource
    private  UserMemoryMapper mapper;



    @Test
    void testMemoryModel(){
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

        SystemMessage systemMessage = SystemMessage.from(GET_MEMORY);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .responseFormat(responseFormat)
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .apiKey(System.getenv("MOONSHOT"))
                .temperature(0.0)
                .baseUrl("https://api.moonshot.cn/v1")
                .modelName("moonshot-v1-128k").build();

        String oldMemories= """
                [
                    {
                      "id": "6g3h1f2",
                      "content": "喜欢喝水",
                      "category": "饮食偏好"
                    },
                    {
                      "id": "3e1m4n5",
                      "content": "喜欢看电影",
                      "category": "娱乐活动"
                    },
                    {
                      "id": "7d4f5g6",
                      "content": "特别喜欢谍影重重",
                      "category": "电影偏好"
                    }
                  ]
                """;

        String chat="在这个充满可能性的时刻，我为您准备了一段由文字构成的奇妙旅程。这段旅程将带您穿越一个由毫无意义的词语组成的迷宫，这个迷宫没有出口，也没有入口，只有无尽的词语在回荡。您可能会发现，这段旅程没有任何实际意义，但它却充满了文字的魅力和语言的奇妙。就像一场没有目的地的旅行，我们只在乎沿途的风景，而不在乎最终的归宿。所以，请您放松心情，跟随我一起进入这个由废话构成的世界吧。在这里，您可以尽情地享受文字的乐趣，而不用担心它们是否有什么实际意义。因为在这个世界里，意义本身就是一种多余的存在，只有废话才是永恒的真理。让我们一起在废话的海洋中畅游，感受那些毫无意义的词语所带来的奇妙体验吧。";
        String chat2="我喜欢喝茶，嘿嘿嘿";
        ChatResponse chatResponse = model.chat(systemMessage, UserMessage.from(oldMemories),UserMessage.from(chat2));
        String response = chatResponse.aiMessage().text();
        String cleanedResponse = response
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        System.out.println(cleanedResponse);
        Memories memories = JSONUtil.toBean(cleanedResponse, Memories.class);
        List<UserMemory> add = memories.getAdd();
        System.out.println("添加"+add);
        System.out.println("更新"+memories.getUpdate());
        System.out.println("删除"+memories.getDelete());
    }

    @Test
    void testEmbeddingModel(){
        Embedding content = embeddingModel.embed("你好，我是谁呢？").content();
        int dimension = content.dimension();
        float[] vector = content.vector();
        System.out.println(dimension+ Arrays.toString(vector));
        UserMemory memory = UserMemory.builder()
                .category("随便")
                .content("内容")
                .embedding(vector)
                .userId(1L).build();
        System.out.println(memory);
        mapper.insert(memory);


    }
}
