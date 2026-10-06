package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.domain.APIConfig;
import com.huzhijian.nexusagentweb.domain.Model;
import com.huzhijian.nexusagentweb.domain.UserConfig;
import com.huzhijian.nexusagentweb.em.ModelType;
import com.huzhijian.nexusagentweb.exception.UnauthorizedException;
import com.huzhijian.nexusagentweb.exception.ValidationException;
import com.huzhijian.nexusagentweb.factory.EncryptorFactory;
import com.huzhijian.nexusagentweb.service.ArtifactService;
import com.huzhijian.nexusagentweb.service.ChatAssistant;
import com.huzhijian.nexusagentweb.service.ChatHistoryListService;
import com.huzhijian.nexusagentweb.service.QuotaService;
import com.huzhijian.nexusagentweb.service.ChatMemoryService;
import com.huzhijian.nexusagentweb.service.UserConfigService;
import com.huzhijian.nexusagentweb.context.RunCancellationRegistry;
import com.huzhijian.nexusagentweb.context.RunUserRegistry;
import com.huzhijian.nexusagentweb.context.UserContextHolder;
import com.huzhijian.nexusagentweb.converter.ChatMessageConverter;
import com.huzhijian.nexusagentweb.factory.ChatContextFactory;
import com.huzhijian.nexusagentweb.observability.RunMetricsReporter;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import com.huzhijian.nexusagentweb.service.impl.ChatServiceImpl;
import com.huzhijian.nexusagentweb.skills.SkillLoader;
import com.huzhijian.nexusagentweb.utils.UrlGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code getModelList(configId)} 的失败路径契约与降级行为。
 *
 * <p><b>为什么必须有这个测试（L1 失败路径契约层）</b>：
 * 2026-10-05 这个方法出过一次 401 —— 旧契约让前端回传 {@code token}，
 * 而前端手上的 Key 是 {@code UserConfigServiceImpl.decryptKey()} 拼出来的
 * <b>打码值</b>（{@code sk****3t5d}），拿去调厂商必然 401。
 * 更糟的是那次 401 <b>穿透成了 500</b>（{@code HttpException} 无人接，
 * 直接掉进 {@code GlobalExceptionHandler} 兜底），把「拉模型列表」这个
 * 锦上添花的动作变成了整页报错。
 *
 * <p>两个必须钉住的行为：
 * <ol>
 *   <li><b>配置不存在 / 不属于当前用户 → 4xx 业务异常，不是 500</b>；</li>
 *   <li><b>厂商不支持 /v1/models、Key 不对、网络不通 → 降级返回已存模型名，绝不 500</b>。
 *       DeepSeek 与小米都不实现 {@code /v1/models}，这是常态而非异常。</li>
 * </ol>
 *
 * <p>真实厂商调用<b>不打桩</b>：那需要外网与真 Key，属于人工集成测试
 * （{@code @Disabled + @Tag("manual")}）。这里只验证「打桩让依赖抛异常时，
 * 返回值不是 null / 不是 500」这一层契约。
 */
@DisplayName("getModelList(configId)：失败路径契约与降级")
class GetModelListContractTest {

    /** 必须是**十六进制**：Encryptors.text(secret, salt) 底层按 hex 解析 salt，
     * 随手写 "unit-test-salt" 会抛 IllegalArgument("Detected a Non-hex character")。 */
    private static final String SALT = "0123456789abcdef";
    private static final String CONFIG_ID = "cfg-1";
    private static final String PLAIN_KEY = "sk-plain-key-for-test";

    private UserConfigService userConfigService;
    private ChatServiceImpl service;

    @BeforeEach
    void setUp() {
        // EncryptorFactory 需要主密钥；surefire 已注入 API_KEY_SECRET，
        // 但显式设一次保证不依赖外部环境
        EncryptorFactory.setConfiguredSecret("nexus-agent-test-only-secret");

        userConfigService = mock(UserConfigService.class);
        // 其余依赖本方法不碰，全给 mock（不打桩任何真实外部调用）
        service = new ChatServiceImpl(
                mock(ChatContextFactory.class),
                mock(ChatHistoryListService.class),
                mock(ChatMessageConverter.class),
                mock(AgentProperties.class),
                mock(SkillLoader.class),
                mock(RunMetricsReporter.class),
                mock(QuotaService.class),
                mock(ArtifactService.class),
                mock(RunUserRegistry.class),
                // 2026-10-06：停止生成用的两个依赖，本用例不碰
                mock(RunCancellationRegistry.class),
                mock(ChatMemoryService.class),
                userConfigService,
                new UrlGuard(false));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.removeUserId();
    }

    private void loginAs(Long userId) {
        UserContextHolder.saveId(userId);
    }

    /** 造一条带加密 Key 的 API 配置 */
    private String encryptedConfigJson(String baseUrl) {
        Model m = new Model();
        m.setName("Pro");
        m.setType(ModelType.CHAT);
        m.setVision(Boolean.TRUE);
        // APIConfig 只有全参构造器（@AllArgsConstructor），没有无参 + setter 链
        APIConfig cfg = new APIConfig(
                CONFIG_ID,                                  // id
                "我的配置",                                   // name
                EncryptorFactory.text(SALT).encrypt(PLAIN_KEY), // APIKey（密文）
                baseUrl,                                    // baseUrl
                List.of(m),                                 // model
                Boolean.TRUE);                              // isDefault
        return JSONUtil.toJsonStr(List.of(cfg));
    }

    private void givenConfig(String baseUrl) {
        UserConfig uc = UserConfig.builder()
                .userId(1L)
                .salt(SALT)
                .llmApiToken(encryptedConfigJson(baseUrl))
                .build();
        when(userConfigService.getUserConfig(1L)).thenReturn(uc);
    }

    @Test
    @DisplayName("未登录 → UnauthorizedException（401，不是 500）")
    void notLoggedIn() {
        // 故意不 loginAs
        assertThrows(UnauthorizedException.class, () -> service.getModelList(CONFIG_ID));
    }

    @Test
    @DisplayName("configId 为空 / 空白 → ValidationException（400）")
    void blankConfigId() {
        loginAs(1L);
        assertThrows(ValidationException.class, () -> service.getModelList(null));
        assertThrows(ValidationException.class, () -> service.getModelList("  "));
    }

    @Test
    @DisplayName("用户没配过 API Key → ValidationException，且提示可读")
    void noConfigYet() {
        loginAs(1L);
        when(userConfigService.getUserConfig(1L)).thenReturn(null);

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.getModelList(CONFIG_ID));
        // 异常消息会直接进 GlobalExceptionHandler 交给前端，不能含 "null" / 空串
        assertNotNull(e.getMessage());
        assertTrue(e.getMessage().contains("尚未配置"),
                () -> "消息应说明未配置，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("盐值缺失（user_config 记录不完整）→ 明确报错，不是 500")
    void missingSalt() {
        loginAs(1L);
        UserConfig uc = UserConfig.builder()
                .userId(1L)
                .salt(null)                       // 关键：模拟脏数据
                .llmApiToken(encryptedConfigJson("https://api.example.com/v1"))
                .build();
        when(userConfigService.getUserConfig(1L)).thenReturn(uc);

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.getModelList(CONFIG_ID));
        assertTrue(e.getMessage().contains("盐值"), () -> "实际：" + e.getMessage());
    }

    @Test
    @DisplayName("configId 指向别人或不存在的配置 → ValidationException，不能泄露密钥")
    void configNotFound() {
        loginAs(1L);
        givenConfig("https://api.example.com/v1");

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.getModelList("cfg-of-another-user"));
        assertTrue(e.getMessage().contains("不存在") || e.getMessage().contains("不属于"),
                () -> "实际：" + e.getMessage());
        // 🔴 绝不能把「配置存在但 Key 解不开」之类的信息透出去
        assertTrue(!e.getMessage().contains(PLAIN_KEY), "异常消息泄露了明文 Key！");
    }

    @Test
    @DisplayName("🔴 降级：厂商 /v1/models 不可用时返回已存模型名，且不是 null")
    void degradesWhenVendorHasNoModelListApi() {
        loginAs(1L);
        // 指向一个真实存在但打不出 /models 的公网域名形状的地址；
        // 实际请求必然失败（无外网 / 无真 Key），走降级分支
        givenConfig("https://api.deepseek.com");

        List<String> result = assertDoesNotThrow(() -> service.getModelList(CONFIG_ID),
                "厂商不支持 /v1/models 时绝不能抛异常 —— 那会让整页 500");

        assertNotNull(result, "降级结果不能是 null（前端会拿到 null 然后崩）");
        // 降级值来自 APIConfig.model 里的显示名
        assertTrue(result.contains("Pro"), () -> "降级应返回已存模型名，实际：" + result);
    }

    @Test
    @DisplayName("降级值不为 null / 不含 \"null\"（前端契约：绝不返回字符串 \"null\"）")
    void fallbackNeverContainsNullLiteral() {
        loginAs(1L);
        givenConfig("https://api.deepseek.com");

        List<String> result = service.getModelList(CONFIG_ID);
        assertNotNull(result);
        for (String s : result) {
            assertTrue(s != null && !s.isBlank(), "降级列表里有空项");
            assertTrue(!s.contains("null"), () -> "降级列表里出现字符串 \"null\"：" + result);
        }
    }

    @Test
    @DisplayName("baseUrl 为空 → 直接降级，不去打厂商")
    void blankBaseUrlDegrades() {
        loginAs(1L);
        givenConfig("");

        List<String> result = service.getModelList(CONFIG_ID);
        assertTrue(result.contains("Pro"), () -> "实际：" + result);
    }

    @Test
    @DisplayName("🔴 SSRF：baseUrl 指向内网 → ValidationException，不带 Key 去请求")
    void blocksInternalAddress() {
        loginAs(1L);
        // 库里存了内网地址（可能是加 UrlGuard 之前写入的旧数据）
        givenConfig("http://192.168.1.10:8000/v1");

        ValidationException e = assertThrows(ValidationException.class,
                () -> service.getModelList(CONFIG_ID));
        assertTrue(e.getMessage().contains("不合法"), () -> "实际：" + e.getMessage());
    }
}
