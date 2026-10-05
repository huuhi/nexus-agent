package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把所有 mapper XML 逐个过一遍真正的 XML 解析器。
 * <p>
 * 为什么要有这个测试：现有测试都是<b>纯单测</b>（不起 Spring 上下文），
 * 所以 mapper XML 写坏了 —— 最典型的是<b>注释里出现连续的两个减号</b>（XML 语法禁止，
 * 曾经用一整排横线当分隔线把注释弄坏）—— 编译和单测<b>全都不会报错</b>，
 * 要等 jar 打包好、真正启动时才在 {@code Failed to parse mapping resource} 上炸掉。
 * 这个测试把发现时机从「服务器启动」提前到「跑单测」。
 *
 * <p><b>🔴 2026-10-05 修：数量断言从 classpath 改为扫源码目录，且不再硬编码数字。</b>
 * 原写法用 {@code classpath*:mapper/*.xml} + {@code >= 10}，踩了两个坑：
 * <ol>
 *   <li><b>classpath 里混着 target 残留</b>：本地知识库下线（commit {@code 92bead5}）删掉了
 *       {@code KnowledgeBaseMapper.xml} / {@code KnowledgeBaseFileMapper.xml}，
 *       但不跑 {@code clean} 时它们仍留在 {@code target/classes/mapper/} 里。
 *       于是「源码只剩 8 份 + 残留 2 份 = 扫到 10 份」，阈值恰好被幽灵文件凑达标 ——
 *       一次<b>假绿</b>。真实数字 8 是在主仓库跑 {@code clean package} 后才暴露的。</li>
 *   <li><b>硬编码 10 是错的</b>：Mapper 接口数 ≠ XML 数。{@code LexiangCredentialMapper}
 *       与 {@code UserSkillMapper} 是 MyBatis-Plus 纯 CRUD（{@code extends BaseMapper}），
 *       <b>设计上就没有 XML</b>（见两者的接口注释）。所以正确关系是
 *       「有 XML 的 Mapper 数 ≤ Mapper 接口总数」，而不是相等。</li>
 * </ol>
 * 改成扫 {@code nexus-agent-mapper/src/main/resources/mapper/}：只含真正入库的文件，
 * 天然不受 build 残留影响；同时保留「一个都没扫到」这个护栏（扫描路径写错时仍会失败）。
 */
@DisplayName("mapper XML 良构性（防注释里带 -- 这类错）")
class MapperXmlParseTest {

    @Test
    @DisplayName("所有 mapper XML 都能被 XML 解析器接受（提前拦住注释里带 -- 这类错）")
    void allMapperXmlsAreWellFormed() throws Exception {
        Path mapperDir = sourceMapperDir();
        List<Path> xmls;
        try (Stream<Path> stream = Files.list(mapperDir)) {
            xmls = stream.filter(p -> p.getFileName().toString().endsWith(".xml")).sorted().toList();
        }

        // 只断言「扫到的东西 > 0」：路径写错 / 目录搬走了要立刻失败。
        // ❌ 不断言具体个数 —— MyBatis-Plus 纯 CRUD 的 Mapper 本来就没有 XML，
        //    写死数字等于给自己埋一颗「以后加个 Mapper 就得改测试」的地雷。
        assertTrue(!xmls.isEmpty(),
                () -> "在 " + mapperDir + " 下没扫到任何 mapper XML，扫描路径可能失效了");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // mapper XML 带 mybatis 的 DOCTYPE，不关掉外部 DTD 加载会去联网拉 mybatis.org
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        for (Path xml : xmls) {
            try (InputStream in = Files.newInputStream(xml)) {
                assertDoesNotThrow(
                        () -> factory.newDocumentBuilder().parse(new InputSource(in)),
                        "mapper XML 解析失败：" + xml.getFileName());
            }
        }
    }

    /**
     * 另一个断言：<b>classpath 里的 XML 数量不应多于源码目录</b>。
     * <p>
     * 这条专门盯 {@code target/classes} 残留 —— 上游删了 XML 但没 clean 时，
     * 运行时 classpath 里会多出已废弃的映射文件，轻则在测试里蒙混过关，
     * 重则让 MyBatis 加载到不该存在的 statement。
     */
    @Test
    @DisplayName("classpath 里的 mapper XML 不得多于源码目录（揪出 target 残留）")
    void classpathHasNoStaleMapperXml() throws Exception {
        Resource[] onClasspath = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/*.xml");

        long sourceCount;
        try (Stream<Path> stream = Files.list(sourceMapperDir())) {
            sourceCount = stream.filter(p -> p.getFileName().toString().endsWith(".xml")).count();
        }

        assertTrue(onClasspath.length <= sourceCount,
                () -> """
                        classpath 里有 %d 份 mapper XML，但源码目录只有 %d 份 —— 多出来的多半是 \
                        target/classes 里的历史残留（上游删了 XML 但没跑 clean）。
                        这会让 MapperXmlParseTest 的数量断言被幽灵文件凑达标而假绿。
                        处理：mvn -pl nexus-agent-web -am clean
                        """.formatted(onClasspath.length, sourceCount));
    }

    /** 源码里的 mapper 目录。surefire 工作目录是模块目录，向上找含 nexus-agent-mapper 的那一层。 */
    private static Path sourceMapperDir() throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            Path candidate = dir.resolve("nexus-agent-mapper/src/main/resources/mapper");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 nexus-agent-mapper/src/main/resources/mapper（向上 5 层都没有）");
    }
}
