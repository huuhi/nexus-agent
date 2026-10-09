package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-09「应用启动不了（二进宫）」事故的防复发护栏。
 * <p>
 * <b>线上现象</b>：后端启动失败，嵌套异常链的末端是
 * <pre>
 * Error creating bean with name 'webSearchTool': Injection of autowired dependencies failed
 * Caused by: PlaceholderResolutionException:
 *     Could not resolve placeholder 'TAVILY_API_KEY' in value "${TAVILY_API_KEY}"
 *   ...chatController ← chatServiceImpl ← chatContextFactory ← toolRegistry ← webSearchTool
 * </pre>
 * <b>根因</b>：{@code WebSearchTool} 的 {@code apiKey} 字段被写成了
 * <pre>
 * &#64;Value("${TAVILY_API_KEY}")
 * private final String apiKey = "";
 * </pre>
 * 这一行同时踩了两个坑（两条都已在 JDK 21 + Spring 6.2.17 上实测复现）：
 * <ol>
 *   <li><b>占位符没有默认值</b> → Spring Boot 的 {@code PropertySourcesPlaceholderConfigurer}
 *       用 {@code resolveRequiredPlaceholders} 解析，环境变量缺失时<b>直接抛异常</b>，
 *       顺着依赖链把整个应用拉挂。而本工具的设计是「没配 Key 就<b>不注册</b>、其它功能照常」——
 *       一个可选能力的缺失被升级成了「全局不可用」。</li>
 *   <li><b>{@code @Value} 打在 {@code final} 字段上</b> → {@code = ""} 让该字段成为<b>编译期常量</b>，
 *       javac 会把类内所有读取<b>常量折叠</b>掉。实测：反射查字段确实被 Spring 写成了 {@code "abc"}，
 *       但同一个类里的 getter 读到的仍然是 {@code ""} —— 注入「看起来成功了」，实际完全没生效。
 *       于是 {@code apiKey != null} 恒为 true，没配 Key 时工具照样注册，然后每次调用 401。</li>
 * </ol>
 * <b>为什么编译和单测都拦不住</b>：占位符能否解析是<b>运行期</b>的事（本地 IDE 常带这个 env，
 * CI 里未必有），而常量折叠后单测里 {@code new WebSearchTool(..., "tvly-test-key")} 照样绿 ——
 * 单测走的是给测试准备的构造器，恰好绕开了 @Value 这条注入路径。
 * <p>
 * <b>本护栏的做法</b>：纯反射扫描组件扫描范围内的类，不需要起 Spring 容器（那需要真实
 * PostgreSQL / Redis / 模型 Key），复查两条判据：{@code @Value} 不能打在 final 字段上；
 * {@code ${...}} 占位符必须带 {@code :默认值}。判据<b>只看写法、不看运行时环境</b>，
 * 所以本地和 CI 结论一致，不存在「本地绿、服务器红」。
 *
 * @see com.huzhijian.nexusagentweb.tools.WebSearchTool 事故原件
 * @see BeanConstructorInjectionGuardTest 同一类事故（构造器形态）的护栏
 */
@DisplayName("@Value 注入（防「占位符没默认值 / 打在 final 字段上」把应用炸掉或静默失效）")
class ValueInjectionGuardTest {

    /** 组件扫描的根包，与 NexusAgentWebApplication 所在包一致 */
    private static final String BASE_PACKAGE = "com.huzhijian.nexusagentweb";

    /** 匹配 {@code ${...}}；嵌套写法（{@code ${a:${b:}}}）的第一段一定含 {@code :}，不会误判 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    @Test
    @DisplayName("被扫描到的 Bean：@Value 不落在 final 字段上，且每个占位符都带默认值")
    void valueInjectionIsSafeToMiss() {
        List<BeanDefinition> candidates = scanCandidates();
        assertTrue(candidates.size() >= 20,
                "只扫到 " + candidates.size() + " 个候选 Bean，扫描逻辑多半失效了 ——"
                        + "护栏静默通过比没有护栏更危险。");

        List<String> offenders = new ArrayList<>();
        for (BeanDefinition bd : candidates) {
            Class<?> type;
            try {
                type = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue; // 加载不了的类交给别的环节处理，本护栏不掺和
            }
            if (type.isInterface() || type.isEnum() || type.isAnnotation()
                    || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                Value value = field.getAnnotation(Value.class);
                if (value == null) {
                    continue;
                }
                String label = type.getSimpleName() + "." + field.getName();
                if (Modifier.isFinal(field.getModifiers())) {
                    offenders.add(label + "（final 字段：" + value.value() + "）");
                }
                checkPlaceholdersHaveDefaults(value, label, offenders);
            }
            // 🔴 构造器 / 方法参数上的 @Value 也要查（2026-10-09 把密钥注入挪到构造器参数后补的）。
            // 参数没有「final 折叠」问题，但「占位符缺默认值 → 整个应用起不来」这条一模一样：
            // 参数解析发生在容器 refresh 期间，一样会把依赖链上所有 bean 一起拉挂。
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                Parameter[] params = ctor.getParameters();
                for (int i = 0; i < params.length; i++) {
                    Value value = params[i].getAnnotation(Value.class);
                    if (value != null) {
                        checkPlaceholdersHaveDefaults(value,
                                type.getSimpleName() + " 构造器参数[" + i + "]", offenders);
                    }
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                Parameter[] params = method.getParameters();
                for (int i = 0; i < params.length; i++) {
                    Value value = params[i].getAnnotation(Value.class);
                    if (value != null) {
                        checkPlaceholdersHaveDefaults(value,
                                type.getSimpleName() + "." + method.getName() + " 参数[" + i + "]", offenders);
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("以下 @Value 写法会让「缺一项可选配置」演变成「整个应用起不来」或「注入静默失效」：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n\n典型症状：Injection of autowired dependencies failed → "
                    + "PlaceholderResolutionException: Could not resolve placeholder 'X'，\n"
                    + "依赖链上一长串 bean 全部创建失败，整个上下文 refresh 挂掉。\n"
                    + "修法：① 去掉 final（或干脆不用 @Value，改由构造器读 System.getenv，"
                    + "密钥类配置一律走这条路 —— 铁律 4）；"
                    + "② 占位符补默认值写成 ${X:}，让「没配」降级为「该功能不启用」。"
                    + "若某项配置确实必须存在，请在启动校验里显式检查并给出可读报错，"
                    + "而不是靠占位符解析失败把容器炸掉。");
        }
    }

    // ==== 反向验证：判据本身要能被证明「抓得住」（写错规则的护栏等于没有护栏）====

    @Test
    @DisplayName("反向验证：坏写法被抓住，项目里在用的好写法全部放行")
    void predicateDistinguishesGoodFromBad() {
        assertTrue(hasFinalValue(resolve("BadFinalField")),
                "final 字段 + @Value + 常量初始值 → 必须报警（这正是本次事故的形态）");
        assertFalse(hasFinalValue(resolve("GoodPlainField")),
                "普通字段上的 @Value → 不该报警");
        assertTrue(hasDefaultlessPlaceholder(resolve("BadNoDefault")),
                "${TAVILY_API_KEY} 没写默认值 → 必须报警");
        assertFalse(hasDefaultlessPlaceholder(resolve("GoodWithDefault")),
                "${X:} 有默认值 → 不该报警");
        assertFalse(hasDefaultlessPlaceholder(resolve("GoodNested")),
                "嵌套 ${X:${Y:}} 每层都有默认值 → 不该报警（现网 WebClientConfig 就是这个写法）");

        // 🔴 构造器参数上的 @Value（2026-10-09 把密钥注入挪到参数后补的判据）
        assertTrue(hasDefaultlessParam(resolve("BadCtorParam")),
                "构造器参数上的 @Value 没写默认值 → 必须报警，后果与字段上完全一样（整个应用起不来）");
        assertFalse(hasDefaultlessParam(resolve("GoodCtorParam")),
                "构造器参数带 :默认值 → 不该报警，这正是我们现在注入 TAVILY Key 的写法");
    }

    /** 坏形态：final + @Value + 字面量初始值（2026-10-09 事故原件） */
    @SuppressWarnings("unused")
    static class BadFinalField {
        @Value("${TAVILY_API_KEY}")
        private final String key = "";
    }

    /** 好形态：普通字段 */
    @SuppressWarnings("unused")
    static class GoodPlainField {
        @Value("${nexus.agent.jwt-secret:}")
        private String secret;
    }

    /** 坏形态：占位符无默认值 */
    @SuppressWarnings("unused")
    static class BadNoDefault {
        @Value("${TAVILY_API_KEY}")
        private String key;
    }

    /** 好形态：占位符带空默认值 */
    @SuppressWarnings("unused")
    static class GoodWithDefault {
        @Value("${nexus.agent.cors.allowed-origins:}")
        private String origins;
    }

    /** 好形态：嵌套占位符，每层都有默认值（现网 WebClientConfig 的写法） */
    @SuppressWarnings("unused")
    static class GoodNested {
        @Value("${nexus.agent.sandbox.base-url:${BASE_URL:}}")
        private String baseUrl;
    }

    /** 坏形态：构造器参数上的 @Value 缺默认值 */
    @SuppressWarnings("unused")
    static class BadCtorParam {
        BadCtorParam(@Value("${TAVILY_API_KEY}") String key) {
        }
    }

    /** 好形态：构造器参数带 :默认值（= 现在 web_search 注入 Key 的写法） */
    @SuppressWarnings("unused")
    static class GoodCtorParam {
        GoodCtorParam(@Value("${nexus.agent.websearch.api-key:${TAVILY_API_KEY:}}") String key) {
        }
    }

    // ==== 内部实现 ====

    /**
     * 校验一个 {@code @Value} 表达式里的每个 {@code ${...}} 都带了 {@code :默认值}。
     * <p>
     * 缺默认值的后果不是「值不对」，而是「<b>缺这项配置时整个应用起不来</b>」：
     * Spring 用 {@code resolveRequiredPlaceholders} 解析，直接抛
     * {@code PlaceholderResolutionException}，把「一个可选功能没配」升级成「全局不可用」。
     */
    private static void checkPlaceholdersHaveDefaults(Value value, String label, List<String> offenders) {
        Matcher m = PLACEHOLDER.matcher(value.value());
        while (m.find()) {
            if (!m.group(1).contains(":")) {
                offenders.add(label + "（占位符 ${" + m.group(1)
                        + "} 没有默认值 → 缺配置时整个应用起不来）");
            }
        }
    }

    private static boolean hasFinalValue(Class<?> type) {
        return findValueField(type) != null
                && Modifier.isFinal(findValueField(type).getModifiers());
    }

    private static boolean hasDefaultlessPlaceholder(Class<?> type) {
        Field field = findValueField(type);
        Matcher m = PLACEHOLDER.matcher(field.getAnnotation(Value.class).value());
        while (m.find()) {
            if (!m.group(1).contains(":")) {
                return true;
            }
        }
        return false;
    }

    /** 反向验证用：该类的某个构造器参数上有没有「缺默认值的 @Value」 */
    private static boolean hasDefaultlessParam(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                Value v = p.getAnnotation(Value.class);
                if (v != null) {
                    Matcher m = PLACEHOLDER.matcher(v.value());
                    while (m.find()) {
                        if (!m.group(1).contains(":")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static Field findValueField(Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(Value.class)) {
                return f;
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " 上没有 @Value 字段，反向验证用例写错了");
    }

    private static Class<?> resolve(String simpleName) {
        try {
            return Class.forName(ValueInjectionGuardTest.class.getName() + "$" + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 扫描所有会被 {@code @SpringBootApplication} 组件扫描命中的类。
     * 用 Spring 自己的扫描器（而不是手搓 classpath 遍历），避免守卫和生产判定各说各话。
     */
    private static List<BeanDefinition> scanCandidates() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // @Component 的元注解覆盖 @Service / @Repository / @Controller / @RestController / @Configuration
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class, true, false));

        List<BeanDefinition> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = bd.getBeanClassName();
            if (name == null) {
                continue;
            }
            // 测试类自身也在同一个根包下，但它们不是 Bean
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (simple.endsWith("Test") || simple.endsWith("Tests") || simple.endsWith("IT")
                    || name.contains("$$")) {
                continue;
            }
            result.add(bd);
        }
        return result;
    }
}
