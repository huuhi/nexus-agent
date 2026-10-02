package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把所有 mapper XML 逐个过一遍真正的 XML 解析器。
 * <p>
 * 为什么要有这个测试：现有测试都是**纯单测**（不起 Spring 上下文），
 * 所以 mapper XML 写坏了 —— 最典型的是<b>注释里出现连续的两个减号</b>（XML 语法禁止，
 * 曾经用一整排横线当分隔线把注释弄坏）—— 编译和单测**全都不会报错**，
 * 要等 jar 打包好、真正启动时才在 {@code Failed to parse mapping resource} 上炸掉。
 * 这个测试把发现时机从「服务器启动」提前到「跑单测」。
 */
class MapperXmlParseTest {

    @Test
    @DisplayName("所有 mapper XML 都能被 XML 解析器接受（提前拦住注释里带 -- 这类错）")
    void allMapperXmlsAreWellFormed() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/*.xml");
        // 10 个 Mapper 接口各对应一份 XML；数量明显不对说明扫描路径出了问题，先失败在这里
        assertTrue(resources.length >= 10,
                "mapper XML 数量异常：只扫到 " + resources.length + " 个");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // mapper XML 带 mybatis 的 DOCTYPE，不关掉外部 DTD 加载会去联网拉 mybatis.org
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        for (Resource resource : resources) {
            String name = resource.getFilename();
            try (InputStream in = resource.getInputStream()) {
                assertDoesNotThrow(
                        () -> factory.newDocumentBuilder().parse(new InputSource(in)),
                        "mapper XML 解析失败：" + name);
            }
        }
    }
}
