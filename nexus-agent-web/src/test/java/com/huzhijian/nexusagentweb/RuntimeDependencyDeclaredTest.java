package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠️ 这个测试**不测代码，测的是「运行时必需的依赖有没有被显式声明」**。
 * <p>
 * 2026-10-05 真的把 Docker 部署搞挂过一次：日志刷屏
 * {@code Failed to load driver class org.postgresql.Driver in either of
 * HikariConfig class loader or Thread context classloader}，
 * 容器直接起不来。根因不一行代码都没改错 ——
 * {@code postgresql} 驱动从来**没有被任何 pom 声明过**，
 * 一直是 {@code langchain4j-pgvector} 顺手带进来的传递依赖。
 * 10-04 下线本地知识库时删掉 pgvector，驱动就跟着消失了。
 * <p>
 * 为什么三道防线都没拦住：
 * <ul>
 *   <li>编译期：JDBC 驱动是运行时按类名反射加载的，编译完全不受影响；</li>
 *   <li>单元测试：全部用 mock 数据源，从不真正 {@code Class.forName} 驱动；</li>
 *   <li>本地 {@code java -jar}：本地 {@code ~/.m2} 里有驱动残留 / 旧 jar 也照样起，
 *       只有干净环境里重新打出的 fat jar 才暴露。</li>
 * </ul>
 * 所以只能把 pom 当**输入**来断言：凡是运行期必须存在的东西，必须有人显式声明，
 * 不许靠「XX 依赖顺便带进来」这种隐式契约。
 *
 * @see EnvPlaceholderDriftTest 同属「静态资产一致性」这一层测试
 */
@DisplayName("运行时必需依赖必须显式声明（不靠传递依赖碰运气）")
class RuntimeDependencyDeclaredTest {

    /**
     * 必须**显式**出现在某个 pom 的 &lt;dependencies&gt; 里的坐标。
     * <p>
     * 选它们的判据：<b>删掉后应用在干净环境里直接起不来，或功能整体失效</b>，
     * 且当前没有任何一个 pom 为它们负责。
     * <ul>
     *   <li>{@code postgresql} —— 2026-10-05 事故主角，DataSource 必需；</li>
     *   <li>{@code mybatis-plus-spring-boot3-starter} —— SqlSessionFactory 必需
     *       （历史上也只有 common 模块声明，位置正确，一并锁住）；</li>
     *   <li>{@code spring-boot-starter-data-redis} —— 验证码 / 配额 / 限频令牌
     *       全靠它，漏了会在运行期抛 NoClassDefFoundError 而不是启动失败，
     *       同样属于「静默到上线才炸」那一类。</li>
     * </ul>
     * 不写版本号：版本一律由父 pom 的 dependencyManagement / Boot 统一管理（AGENTS.md 铁律 3）。
     */
    private static final List<String> REQUIRED = List.of(
            "org.postgresql:postgresql",
            "com.baomidou:mybatis-plus-spring-boot3-starter",
            "org.springframework.boot:spring-boot-starter-data-redis"
    );

    /** 一个 &lt;dependency&gt; 块：groupId 与 artifactId 顺序不固定，分开抓更稳 */
    private static final Pattern DEP_BLOCK =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([^<]+?)\\s*</groupId>");
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");

    /** surefire 的工作目录是模块目录，向上找到含 pom.xml 的那一层 */
    private static Path repoRoot() throws IOException {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("nexus-agent-web"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IOException("找不到仓库根目录（向上 5 层都没有 nexus-agent-web 兄弟目录）");
    }

    private static List<Path> allPoms(Path root) throws IOException {
        try (var stream = Files.walk(root, 2)) {
            return stream.filter(p -> p.getFileName().toString().equals("pom.xml")).toList();
        }
    }

    /** 扫出所有 pom 里显式声明的坐标。dependencyManagement 里的也算（那是版本，不是引入）。 */
    private static Set<String> declaredCoordinates(Path root) throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path pom : allPoms(root)) {
            String xml = Files.readString(pom);
            Matcher m = DEP_BLOCK.matcher(xml);
            while (m.find()) {
                Matcher g = GROUP_ID.matcher(m.group(1));
                Matcher a = ARTIFACT_ID.matcher(m.group(1));
                if (g.find() && a.find()) {
                    found.add(g.group(1) + ":" + a.group(1));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("postgresql / mybatis-plus / data-redis 都必须有人显式声明")
    void runtimeDependenciesAreExplicitlyDeclared() throws IOException {
        Set<String> declared = declaredCoordinates(repoRoot());

        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!declared.contains(required)) {
                missing.add(required);
            }
        }

        assertTrue(missing.isEmpty(),
                () -> """
                        以下运行时必需依赖没有任何 pom 显式声明：
                        %s
                        当前显式声明的坐标：%s
                        若它们只是靠别的依赖传递进来的，一旦那个上游依赖被删/被下线，
                        编译期与单元测试都不会报警，只有干净环境里打出的可执行 jar 会起不来。
                        请在数据访问层（本项目是 nexus-agent-common）显式声明，且不要写死版本。
                        """.formatted(missing, declared));
    }
}
