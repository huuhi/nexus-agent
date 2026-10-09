package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-08「容器起不来」事故的防复发护栏。
 * <p>
 * <b>线上现象</b>：Docker 里的后端反复启动失败，日志最后一行是
 * <pre>
 * Caused by: java.lang.BeanInstantiationException:
 *     Failed to instantiate [com.huzhijian.nexusagentweb.tools.WebSearchTool]:
 *     No default constructor found
 *   ...chatController ← chatServiceImpl ← chatContextFactory ← toolRegistry ← webSearchTool
 * </pre>
 * <b>根因</b>（一句话）：Spring 只会在「这个类<b>恰好有一个</b>构造器」时无条件用它做注入。
 * {@code WebSearchTool} 为了给单测注入显式 API Key，额外加了一个包级可见的 3 参构造器，
 * 于是它有了 2 个构造器、又都没标 {@code @Autowired}，Spring 选不出来，
 * 退回「无参构造 + 字段注入」—— 而它没有无参构造器，上下文 refresh 当场失败。
 * 修法是给生产用的那个构造器补 {@code @Autowired}。
 * <p>
 * <b>为什么本地没发现</b>（三道防线全部失效，这个坑的形状值得记住）：
 * <ul>
 *   <li>编译期：加构造器不产生任何编译错误；</li>
 *   <li>单元测试：{@code WebSearchToolTest} 直接用 3 参构造器 new，完全跑得通 ——
 *       <b>单测越方便，越遮住这个错</b>；</li>
 *   <li>唯一会加载 Spring 上下文的 {@code NexusAgentWebApplicationTests} 是
 *       {@code @Disabled}（要真实 PostgreSQL / Redis / 模型 Key，不能进 CI）。</li>
 * </ul>
 * 于是一个「100% 起不来」的版本，带着全绿的测试出现在了服务器上。
 * <p>
 * <b>本护栏的做法</b>：不需要起 Spring 容器（那需要真实环境），而是<b>纯反射</b>复查每个
 * 会被组件扫描到的类的构造器形态。判据就是上面那条规则本身：
 * <blockquote>
 * 既没有无参构造器、也没有 {@code @Autowired} 构造器，而构造器数量 &gt; 1 → Spring 必然实例化失败。
 * </blockquote>
 * 这条判据是<b>完备</b>的：有或无参、或标了 {@code @Autowired}，Spring 都能选出唯一解；
 * 其余情形（多个构造器且无从下手）必炸，不存在却能启动的形态，所以不会误报、也不会漏报。
 * <p>
 * ⚠️ <b>不覆盖</b>：通过 {@code @Bean} 方法手工构造的类（本类本身不会被扫描成 bean，
 * 由使用者自己 new / 自己取构造器），以及 CDN 式条件装配失效的场景 —— 那类问题
 * 由 {@code StartupConfigValidator} 与人工集成测试负责。
 *
 * @see com.huzhijian.nexusagentweb.tools.WebSearchTool 事故原件
 */
@DisplayName("Spring Bean 构造器注入（防「多构造器无 @Autowired」把整个上下文炸掉）")
class BeanConstructorInjectionGuardTest {

    /** 组件扫描的根包，与 NexusAgentWebApplication 所在包一致 */
    private static final String BASE_PACKAGE = "com.huzhijian.nexusagentweb";

    @Test
    @DisplayName("被扫描到的 Bean：要么有无参构造器，要么有 @Autowired 构造器，且不能构造器二义")
    void everyScannedBeanHasAnUnambiguousConstructor() {
        List<BeanDefinition> candidates = scanCandidates();
        assertTrue(candidates.size() >= 20,
                "只扫到 " + candidates.size() + " 个候选 Bean，扫描逻辑多半失效了 ——"
                        + "护栏静默通过比没有护栏更危险（2026-10-06 的 MemoryWindowDriftTest 就是这么废掉的）。");

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
            if (isAmbiguous(type)) {
                offenders.add(type.getName() + "（构造器 " + describeConstructors(type) + "）");
            }
        }

        if (!offenders.isEmpty()) {
            fail("以下 Bean 有多个构造器、既没有无参构造器也没有标 @Autowired 的构造器，"
                    + "Spring 选不出构造器会退回无参实例化，直接把应用上下文炸掉：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n\n典型症状：BeanInstantiationException: Failed to instantiate [X]: "
                    + "No default constructor found —— 而且往往是整个应用启动不了，不是某个接口报错。\n"
                    + "修法：给生产用的那个构造器加 @Autowired（若另一个构造器只是为了单测方便而存在，"
                    + "保留它没问题，但要确保注入入口显式且唯一）。");
        }
    }

    // ==== 反向验证：判据本身要能被证明「抓得住」（写错规则的护栏等于没有护栏）====

    @Test
    @DisplayName("反向验证：坏形态被判定为二义，好形态被放行")
    void predicateDistinguishesGoodFromBad() {
        assertTrue(isAmbiguous(TwoCtorsNoDefault.class),
                "两个构造器且都没有无参 → 必须判定为二义（这正是 WebSearchTool 的形态）");
        assertFalse(isAmbiguous(SingleCtor.class),
                "只有一个构造器 → Spring 会无条件用它，不该报警");
        assertFalse(isAmbiguous(TwoCtorsWithNoArg.class),
                "有无参构造器 → Spring 退回无参实例化，能起来，不该报警");
        assertFalse(isAmbiguous(TwoCtorsButAutowired.class),
                "标了 @Autowired → Spring 有明确指向，不该报警");
    }

    /** 坏形态：两个构造器，都没有无参，也没标 @Autowired */
    static class TwoCtorsNoDefault {
        TwoCtorsNoDefault(String a) { }

        TwoCtorsNoDefault(String a, int b) { }
    }

    /** 好形态：唯一构造器（项目里绝大多数 Bean 都是这种） */
    static class SingleCtor {
        SingleCtor(String a) { }
    }

    /** 好形态：多个构造器但有无参可用 */
    static class TwoCtorsWithNoArg {
        TwoCtorsWithNoArg() { }

        TwoCtorsWithNoArg(String a) { }
    }

    /** 好形态：多个构造器但生产用的那个标了 @Autowired（本次修完的 WebSearchTool） */
    static class TwoCtorsButAutowired {
        @Autowired
        TwoCtorsButAutowired(String a) { }

        TwoCtorsButAutowired(String a, int b) { }
    }

    // ==== 内部实现 ====

    /**
     * 判定「Spring 拿这个类会不会实例化失败」。
     * 见类注释里的完备性说明：无 either/or 之外的第三种活路。
     */
    private static boolean isAmbiguous(Class<?> type) {
        List<Constructor<?>> ctors = Arrays.stream(type.getDeclaredConstructors())
                .filter(c -> !c.isSynthetic())
                .toList();
        if (ctors.size() <= 1) {
            return false; // 唯一构造器（含无参）：Spring 一定能处理
        }
        boolean hasNoArg = ctors.stream().anyMatch(c -> c.getParameterCount() == 0);
        boolean hasAutowired = ctors.stream().anyMatch(c -> c.isAnnotationPresent(Autowired.class));
        return !hasNoArg && !hasAutowired;
    }

    private static String describeConstructors(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .map(c -> c.getParameterCount() + " 参" + (c.isAnnotationPresent(Autowired.class) ? "(@Autowired)" : ""))
                .toList()
                .toString();
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
