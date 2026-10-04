package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.utils.AliOssUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 「配置写了」不等于「配置生效」—— 这一类问题在纯单测里**永远测不到**，
 * 因为单测里根本没有 Spring 的 Environment。
 * <p>
 * 这里用 {@link ApplicationContextRunner} + {@link ConfigDataApplicationContextInitializer}
 * 起一个**真实的迷你 Spring 容器**（会真正加载 `application.yml`），
 * 只挂载需要校验的那几个 Properties Bean —— 不连数据库、不起 Tomcat，秒级完成。
 * <p>
 * 覆盖两类真实事故：
 * <ol>
 *   <li>yml 键名写错 / 被 profile 覆盖 → 值静默变成默认值；</li>
 *   <li>上传 502：根因是 Tomcat 的 {@code maxSwallowSize} 默认只有 2MB（2026-10-04）。</li>
 * </ol>
 */
@DisplayName("关键运行配置必须真的被 Spring 读到（而不只是写在 yml 里）")
class RuntimeConfigBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ServerProperties.class, MultipartProperties.class})
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropsConfig.class)
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class));

    @Test
    @DisplayName("上传大小上限：单文件 20MB / 整个请求 30MB")
    void uploadLimitsAreBound() {
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure(), "迷你容器启动失败：" + ctx.getStartupFailure());
            MultipartProperties props = ctx.getBean(MultipartProperties.class);
            assertEquals(DataSize.ofMegabytes(20), props.getMaxFileSize(),
                    "spring.servlet.multipart.max-file-size 没生效（写错键名或被 profile 覆盖了）");
            assertEquals(DataSize.ofMegabytes(30), props.getMaxRequestSize(),
                    "spring.servlet.multipart.max-request-size 没生效");
        });
    }

    @Test
    @DisplayName("Tomcat 吞请求体的上限必须放开（-1），否则上传超限会断连 → 网关 502")
    void tomcatSwallowSizeIsUnlimited() {
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure(), "迷你容器启动失败：" + ctx.getStartupFailure());
            ServerProperties server = ctx.getBean(ServerProperties.class);
            assertEquals(-1L, server.getTomcat().getMaxSwallowSize().toBytes(),
                    "server.tomcat.max-swallow-size 应为 -1：默认值 2MB 会让超限上传直接断连");
        });
    }

    /**
     * 回归保护：OSS 曾 catch 了 {@code com.aliyuncs.exceptions.ClientException}（core 包），
     * 而 SDK 真正抛的是 {@code com.aliyun.oss.ClientException} —— 两个同名类、包名只差一点，
     * 编译能过但 catch 永不生效。现在方法不再声明任何受检异常，锁死这个行为。
     */
    @Test
    @DisplayName("OSS 工具方法不得再声明 com.aliyuncs 的受检异常（同名类陷阱）")
    void ossUtilMustNotDeclareWrongCheckedException() {
        for (Method method : AliOssUtil.class.getDeclaredMethods()) {
            for (Class<?> thrown : method.getExceptionTypes()) {
                assertNotEquals("com.aliyuncs.exceptions.ClientException", thrown.getName(),
                        "方法 " + method.getName() + " 又声明了 com.aliyuncs 的 ClientException —— "
                                + "OSS SDK 抛的是 com.aliyun.oss.ClientException，这个 catch 永远不会生效");
            }
        }
    }
}
