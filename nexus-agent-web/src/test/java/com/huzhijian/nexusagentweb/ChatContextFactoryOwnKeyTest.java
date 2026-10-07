package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.config.PgChatMemoryStore;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.dto.ModelDTO;
import com.huzhijian.nexusagentweb.em.ModelType;
import com.huzhijian.nexusagentweb.factory.ChatContextFactory;
import com.huzhijian.nexusagentweb.model.ChatModelFactory;
import com.huzhijian.nexusagentweb.model.ModelCapabilityResolver;
import com.huzhijian.nexusagentweb.model.SystemModelRegistry;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.McpInformationService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import com.huzhijian.nexusagentweb.tools.registry.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「本次对话是否走用户自带 Key」的判定（2026-10-07）。
 * <p>
 * <b>用户原话</b>：「为啥用自己的模型还报额度没了的错」——
 * 平台 token 配额无条件校验，自带 Key 的用户也被拦，而费用根本不是平台出的。
 * 修法是引入本判定：自带 Key 时跳过平台配额。
 * <p>
 * ⚠️ <b>这个判定必须与「实际用谁的 Key」同源</b>（都走 {@code matchModel}）。
 * 两处口径一旦分叉，就会出现「判定说自带 Key 放行、实际却走了平台 Key」的漏拦 ——
 * 那等于白送平台成本，而且更难查。
 */
@DisplayName("自带 Key 判定 —— 决定平台 token 配额该不该拦这次对话")
class ChatContextFactoryOwnKeyTest {

    private static final String CONFIG_ID = "cfg-1";
    private static final String MODEL_NAME = "Pro";

    private ChatContextFactory factory(UserConfigService userConfigService) {
        return new ChatContextFactory(
                mock(ObjectProvider.class),
                mock(PgChatMemoryStore.class),
                mock(ToolRegistry.class),
                mock(McpInformationService.class),
                userConfigService,
                new AgentProperties(),
                mock(SkillLoader.class),
                mock(ModelCapabilityResolver.class),
                mock(ChatModelFactory.class),
                mock(SystemModelRegistry.class));
    }

    /** 用户配置里有一条自带 Key 的 API 配置，含 CHAT 类型的模型 Pro */
    private UserConfig userConfigWithOwnKey() {
        Model model = new Model();
        model.setName(MODEL_NAME);
        model.setType(ModelType.CHAT);
        APIConfig config = new APIConfig(CONFIG_ID, "我的配置", "cipher-text",
                "https://api.example.com/v1", List.of(model), Boolean.TRUE);
        return UserConfig.builder()
                .userId(1L)
                .salt("salt")
                .llmApiToken(JSONUtil.toJsonStr(List.of(config)))
                .build();
    }

    @Test
    @DisplayName("用户配了且请求命中该模型 → 自带 Key（配额不该拦）")
    void ownKeyIsDetected() {
        UserConfigService service = mock(UserConfigService.class);
        when(service.getUserConfig(anyLong())).thenReturn(userConfigWithOwnKey());

        assertTrue(factory(service).usesUserProvidedModel(new ModelDTO(CONFIG_ID, MODEL_NAME, false), 1L),
                "用户自带 Key 时必须识别出来，否则会被平台配额误拦（用户投诉的正是这个）");
    }

    @Test
    @DisplayName("用户没配任何 API 配置 → 走平台 Key（配额要拦）")
    void withoutConfigUsesPlatformKey() {
        UserConfigService service = mock(UserConfigService.class);
        when(service.getUserConfig(anyLong())).thenReturn(null);

        assertFalse(factory(service).usesUserProvidedModel(new ModelDTO(null, "deepseek-chat", false), 1L),
                "没有自带配置时按平台 Key 处理 —— 否则等于白送平台成本");
    }

    @Test
    @DisplayName("请求未指定模型 → 走平台 Key（配额要拦）")
    void nullModelUsesPlatformKey() {
        UserConfigService service = mock(UserConfigService.class);
        when(service.getUserConfig(anyLong())).thenReturn(userConfigWithOwnKey());

        assertFalse(factory(service).usesUserProvidedModel(null, 1L));
    }

    @Test
    @DisplayName("userId 为 null → false（不做放行，避免未鉴权请求绕过配额）")
    void nullUserIdIsNotOwnKey() {
        assertFalse(factory(mock(UserConfigService.class)).usesUserProvidedModel(
                new ModelDTO(CONFIG_ID, MODEL_NAME, false), null));
    }
}
