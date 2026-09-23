package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilderFactory;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.openai.OpenAiModelCatalog;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * ⚠️ 人工集成测试，**默认禁用**。
 * <p>
 * 本类需要完整的运行环境（PostgreSQL / Redis / 可用的模型 API Key），
 * 且 getModelList 会真实请求外部模型服务，不适合放进 CI。
 * <p>
 * 需要人工验证时，去掉下面的 @Disabled 再单独运行本类。
 */
@Disabled("人工集成测试：需要真实运行环境与外部模型服务，需要时手动运行")
@Tag("manual")
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
