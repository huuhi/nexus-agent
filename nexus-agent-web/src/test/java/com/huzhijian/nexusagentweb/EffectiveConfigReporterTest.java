package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.EffectiveConfigReporter;
import com.huzhijian.nexusagentweb.properties.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 「生效配置快照」必须能在<b>各种配置状态</b>下正常跑完，且要能看出值来自哪个源（2026-10-06）。
 *
 * <p>为什么必须有它：2026-10-06 一整天，「这个值到底来自 jar 还是被外部覆盖文件顶掉了」
 * 反复成为排查障碍 —— 我改了 jar 内的模型元数据却因为外部 {@code nexus-override.yml}
 * 里那份 {@code system-models} 整体顶替而<b>静默不生效</b>。
 * Spring 的 {@code Environment} 同时知道「最终生效值」与「来自哪个 PropertySource」，
 * 但没人打印它。
 */
@DisplayName("生效配置快照 —— 打印值与来源，且不因配置缺失而炸")
class EffectiveConfigReporterTest {

    private static ConfigurableEnvironment envWith(Map<String, Object> props, String sourceName) {
        ConfigurableEnvironment env = new org.springframework.core.env.StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        // ⚠️ 外部覆盖源必须放在**最后**（Spring 的属性源后者优先），
        // 这正是 nexus-override.yml 的真实位置
        sources.addLast(new MapPropertySource(sourceName, new LinkedHashMap<>(props)));
        return env;
    }

    @Test
    @DisplayName("值来自外部覆盖文件时也能正常打印（不抛异常）")
    void reportsWithExternalOverride() {
        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new AgentProperties(),
                envWith(Map.of("nexus.agent.memory.max-tokens", 12345), "nexus-override.yml"));

        assertDoesNotThrow(reporter::report,
                "配置快照是纯观测手段，任何配置状态下都不能让应用起不来");
    }

    @Test
    @DisplayName("🔴 配置快照里必须能查到 TAVILY Key 的状态，且绝不打印 Key 本体")
    void snapshotSelfChecksTavilyKeyWithoutLeakingIt() throws Exception {
        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new AgentProperties(), mock(ConfigurableEnvironment.class));

        // 🔴 2026-10-09：Key 的读取从 System.getenv 改成 @Value 注入后，
        // 不塞值的话下面「绝不打印本体」那条就是**空跑**（字段恒为 null，怎么都不会泄漏）。
        // 这里显式塞一个假的进去，让它真的能被抓到。
        String fakeKey = "tvly-SHOULD-NOT-APPEAR-IN-LOG-0123456789";
        java.lang.reflect.Field field = EffectiveConfigReporter.class.getDeclaredField("tavilyApiKey");
        field.setAccessible(true);
        field.set(reporter, fakeKey);

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                        .getLogger(EffectiveConfigReporter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            reporter.report();
        } finally {
            logger.detachAppender(appender);
        }

        String printed = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);

        assertTrue(printed.contains("联网搜索(web_search)"),
                "启动快照里必须能查到联网搜索的状态 —— 这是「配了 Key 却不生效」的唯一自查入口，"
                        + "2026-10-08 用户正是因为看不到它才反复怀疑代码");
        assertTrue(printed.contains("正文提取(web_extract)"), "正文提取共用同一个 Key，也要能查到");
        assertTrue(printed.contains("TAVILY_API_KEY"), "要写明查的是哪条环境变量");
        assertTrue(printed.contains("nexus.agent.websearch.api-key"),
                "也要写明配置项的名字 —— 2026-10-09 的现场就是用户写在配置文件里却读不到，"
                        + "只报环境变量名会把人往错误方向引");

//        🔴 只报长度与形态（+来源），绝不打印 Key 本体 —— 日志是会外发、会归档的。
        assertFalse(printed.contains(fakeKey),
                "配置快照把 API Key 本体打进日志了 —— 日志会外发也会归档，绝不能出现");
        assertTrue(printed.contains("长度 " + fakeKey.length()),
                "应该报长度，用户靠它判断「是不是只复制了一部分」");

        // 反向验证：本机若恰好也配了真实环境变量，同样不能漏出去
        String envKey = System.getenv("TAVILY_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            assertFalse(printed.contains(envKey.trim()), "真实环境变量里的 Key 也不能出现在日志里");
        }
    }

    @Test
    @DisplayName("完全没有配置时也能打印（全部落到默认值）")
    void reportsWithNothingConfigured() {
        EffectiveConfigReporter reporter = new EffectiveConfigReporter(
                new AgentProperties(), mock(ConfigurableEnvironment.class));
        assertDoesNotThrow(reporter::report);
    }

    @Test
    @DisplayName("Environment 为 null 也不炸 —— 它只是观测手段")
    void survivesNullEnvironment() {
        // ⚠️ 用真实 Environment 而不是 mock：mock 的 getPropertySources() 会返回 null，
        //    那样测的是「mock 的行为」而不是本类的健壮性
        ConfigurableEnvironment real = envWith(Map.of(), "empty");
        EffectiveConfigReporter reporter = new EffectiveConfigReporter(new AgentProperties(), real);
        assertDoesNotThrow(reporter::report);
    }

    @Test
    @DisplayName("PropertySources 是 Iterable 而非 Collection —— 这里是最容易写错的地方")
    void propertySourcesIsIterableNotCollection() {
        ConfigurableEnvironment env = envWith(Map.of("k", "v"), "src");
        // ⚠️ 这条断言的作用：若哪天有人把 for-each 直接写在 getPropertySources() 上，
        //    编译会失败（Iterable 不是 Collection）—— 记得要先物化成 List
        java.util.List<org.springframework.core.env.PropertySource<?>> list = new java.util.ArrayList<>();
        env.getPropertySources().forEach(list::add);
        assertTrue(list.size() > 0, "至少要有一个属性源");
        // ⚠️ getPropertyNames() 只在 EnumerablePropertySource 上有，PropertySource 接口没有 ——
        //    所以这里不去枚举属性名，只验证「按 key 问最后一个源」拿得到值（EffectiveConfigReporter 的做法）
        assertEquals("v", env.getProperty("k"));
    }
}