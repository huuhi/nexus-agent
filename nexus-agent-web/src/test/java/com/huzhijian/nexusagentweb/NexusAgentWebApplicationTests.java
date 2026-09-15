package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class NexusAgentWebApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void getModelList(){
        new JdkHttpClientBuilderFactory().create();
        List<ModelDescription> abc = OpenAiModelCatalog
                .builder()
                .apiKey("abc")
                .baseUrl("https://api.deepseek.com")
                .httpClientBuilder(new SpringRestClientBuilderFactory().create())
                .build().listModels();
        System.out.println(abc.size());
        String jsonStr = JSONUtil.toJsonStr(abc.toString());
        System.out.println(jsonStr);
    }

}
