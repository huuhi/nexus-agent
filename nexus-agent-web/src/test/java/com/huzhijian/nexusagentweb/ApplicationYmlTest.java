package com.huzhijian.nexusagentweb;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件自身的"能不能被 Spring Boot 解析"测试。
 * <p>
 * <b>为什么需要它</b>：曾经在 {@code application.yml} 里写了两个同名的顶层键 {@code spring:}
 * （一个放 application/profiles/threads，另一个放 lifecycle）。
 * Spring Boot 的 YAML 加载器对重复键是**直接抛异常**的：
 * {@code found duplicate key spring} —— 结果是**应用根本起不来**，
 * 而且这个错误只在启动瞬间出现一次、位置指向 YAML 而不是你的业务代码，非常难归因。
 * <p>
 * 这类问题编译期发现不了、单测也覆盖不到，只有"真的启动一次"才会暴露；
 * 这个测试就是把它挪进 CI：改坏 yml 会立刻红。
 */
class ApplicationYmlTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    @DisplayName("application.yml 能被 Spring Boot 解析（含重复顶层键检查）")
    void applicationYmlParses() throws IOException {
        assertParses("application.yml");
    }

    @Test
    @DisplayName("application-prod.yml 能被 Spring Boot 解析")
    void applicationProdYmlParses() throws IOException {
        assertParses("application-prod.yml");
    }

    @Test
    @DisplayName("application-dev.yml 若存在则必须能解析（本地私有文件，可能不存在）")
    void applicationDevYmlParsesIfPresent() throws IOException {
        Resource res = new ClassPathResource("application-dev.yml");
        if (!res.exists()) {
            // 该文件被 .gitignore 忽略，CI 上没有是正常的 —— 跳过而不是失败
            return;
        }
        assertParses("application-dev.yml");
    }

    @Test
    @DisplayName("回归：重复顶层键必须被判定为解析失败（确认这个测试真的有效）")
    void duplicateTopLevelKeyIsRejected() {
        String bad = "spring:\n"
                + "  application:\n"
                + "    name: demo\n"
                + "other: 1\n"
                + "spring:\n"
                + "  lifecycle:\n"
                + "    timeout-per-shutdown-phase: 30s\n";
        org.springframework.core.io.Resource res =
                new org.springframework.core.io.ByteArrayResource(bad.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 若哪天 Spring Boot 改成"允许重复键"，这个断言会失败 ——
        // 那正好提醒我们：本文件的存在意义需要重新评估。
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> loader.load("bad", res));
    }

    private void assertParses(String name) throws IOException {
        Resource res = new ClassPathResource(name);
        assertTrue(res.exists(), "配置文件应存在于 classpath: " + name);

        List<PropertySource<?>> sources = loader.load(name, res);
        assertFalse(sources.isEmpty(), name + " 解析后应至少产出一个 PropertySource");
    }
}
