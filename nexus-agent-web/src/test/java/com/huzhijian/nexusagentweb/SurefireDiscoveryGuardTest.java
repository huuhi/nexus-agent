package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.RepeatedTest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2026-10-09「护栏其实一直没跑」事故的防复发护栏。
 * <p>
 * <b>事故</b>：{@code ExternalConfigOverrideProbe} 这个名字<b>不匹配 surefire 的默认 include
 * 模式</b>（{@code *Test} / {@code *Tests} / {@code Test*} / {@code *TestCase}），
 * 于是它<b>从来没有被 {@code mvn test} 执行过</b>。
 * 更阴的是两点：
 * <ol>
 *   <li>它的报告文件会因为早先某次手动 {@code -Dtest=} 运行而留在 {@code target/surefire-reports} 里
 *       —— 检查时"有报告"，看起来跑了；</li>
 *   <li>它还被 README 与部署指南当作「<b>已实测验证</b>」的依据引用，
 *       而全量测试的计数里从来没有它。</li>
 * </ol>
 * 换句话说：一个<b>不出声的、绿色的缺席</b>。项目里已经有一条同类教训
 * （{@code MemoryWindowDriftTest} 静默失效，见 {@code SseTimeoutDriftTest} 的注释），
 * 这是第二次，所以这次不再靠"记得"，而是用判据把它挡住。
 * <p>
 * <b>判据</b>：凡是含有 JUnit 测试方法（{@code @Test} / {@code @ParameterizedTest} /
 * {@code @RepeatedTest} / {@code @TestFactory} / {@code @TestTemplate}）的类，
 * 其<b>类名</b>必须匹配 surefire 的默认 include 模式。
 * <p>
 * ⚠️ 刻意<b>不</b>要求「所有测试源文件都叫 *Test」—— 测试目录里还有辅助类，
 * 那样会误报。只钉住「带测试方法的类」，这条判据是完备的：
 * 名字不匹配 = surefire 一定不会执行它，不存在"能跑但名字不对"的形态。
 * <p>
 * ⚠️ 用 {@code Class.forName(name, false, loader)} 加载（<b>不初始化</b>），
 * 避免测试类的静态初始化块在扫描阶段产生副作用。
 */
@DisplayName("测试类名必须能被 surefire 发现（防「护栏写了却从来不跑」）")
class SurefireDiscoveryGuardTest {

    /** 与 surefire 默认 include 一致：Test* / *Test / *Tests / *TestCase */
    private static boolean discoverableBySurefire(String simpleName) {
        return simpleName.startsWith("Test")
                || simpleName.endsWith("Test")
                || simpleName.endsWith("Tests")
                || simpleName.endsWith("TestCase");
    }

    private static boolean hasJUnitTestMethod(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Test.class)
                    || m.isAnnotationPresent(ParameterizedTest.class)
                    || m.isAnnotationPresent(RepeatedTest.class)
                    || m.isAnnotationPresent(TestFactory.class)
                    || m.isAnnotationPresent(TestTemplate.class)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("🔴 带 @Test 的类，名字必须匹配 surefire 默认 include —— 否则它永远不会被执行")
    void everyTestClassIsDiscoverable() throws Exception {
        Path testClasses = testClassesRoot();
        List<String> candidates = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(testClasses)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    // 内部类（含匿名/局部）由 JUnit 通过外部类发现，不单独判定
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .forEach(p -> candidates.add(toClassName(testClasses, p)));
        }

        assertTrue(candidates.size() >= 50,
                "只扫到 " + candidates.size() + " 个测试类，扫描逻辑多半失效了 ——"
                        + "护栏静默通过比没有护栏更危险。期望的根目录：" + testClasses);

        List<String> offenders = new ArrayList<>();
        int withTests = 0;
        for (String name : candidates) {
            Class<?> type;
            try {
                // false = 不初始化：扫描阶段绝不能触发测试类的静态初始化
                type = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            } catch (Throwable t) {
                continue; // 加载不了的类交给别的环节，本护栏不掺和
            }
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                continue; // 抽象基类 / 接口不会被 surefire 执行，也不该被判定
            }
            if (!hasJUnitTestMethod(type)) {
                continue; // 辅助类（夹具、工具）不算测试类
            }
            withTests++;
            String simple = type.getSimpleName();
            if (!discoverableBySurefire(simple)) {
                offenders.add(type.getName());
            }
        }

        // 反向保护：如果判据写歪了（比如 hasJUnitTestMethod 恒为 false），这里会立刻暴露
        assertTrue(withTests >= 40,
                "只识别出 " + withTests + " 个「带测试方法的类」，判据多半失效了");

        if (!offenders.isEmpty()) {
            fail("以下类含有 JUnit 测试方法，但类名不匹配 surefire 的默认 include 模式"
                    + "（Test* / *Test / *Tests / *TestCase），**mvn test 永远不会执行它们**：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n\n危险之处在于它不出声：报告文件可能因为某次手动 -Dtest= 运行而留在 target 里，"
                    + "看起来「有报告」，全量跑却根本没有它。\n"
                    + "修法：改名为 XxxTest / XxxTests（同时改文件名）。\n"
                    + "2026-10-09：ExternalConfigOverrideProbe 就是这么被漏掉的 —— 而 README 与部署指南"
                    + "都把它当作「已实测验证」的依据在引用。");
        }
    }

    // ==================== 反向验证：判据本身要能证明「抓得住」 ====================

    @Test
    @DisplayName("反向验证：坏名字被判定为不可发现，四种好名字都要放行")
    void predicateDistinguishesGoodFromBad() {
        assertTrue(discoverableBySurefire("FooTest"), "*Test 是 surefire 默认模式");
        assertTrue(discoverableBySurefire("FooTests"), "*Tests 是 surefire 默认模式");
        assertTrue(discoverableBySurefire("TestFoo"), "Test* 是 surefire 默认模式");
        assertTrue(discoverableBySurefire("FooTestCase"), "*TestCase 是 surefire 默认模式");
        assertFalse(discoverableBySurefire("ExternalConfigOverrideProbe"),
                "这正是 2026-10-09 被漏掉那个名字：含 @Test 却永远不会被执行");
        assertFalse(discoverableBySurefire("FooHelper"), "普通辅助类名不该被误认为可发现");
    }

    // ==================== 内部实现 ====================

    /**
     * 定位 {@code target/test-classes} 的根目录。
     * <p>
     * 从本类自身的 class 文件位置反推：本类一定在 test-classes 下，
     * 沿包名往上退即可 —— 比读 {@code user.dir} 再拼路径稳（后者在 IDE / surefire 下口径不同）。
     */
    private static Path testClassesRoot() throws Exception {
        URL self = SurefireDiscoveryGuardTest.class.getProtectionDomain()
                .getCodeSource().getLocation();
        Path root = Path.of(self.toURI());
        // 防御：某些运行方式下 code source 是 jar，这里只处理目录形态
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("测试类不在目录里（" + root + "），本护栏无法扫描");
        }
        return root;
    }

    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

}
