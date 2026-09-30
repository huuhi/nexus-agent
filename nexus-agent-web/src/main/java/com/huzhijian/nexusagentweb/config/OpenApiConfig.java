package com.huzhijian.nexusagentweb.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置（P3-4）。
 * <p>
 * 只负责**文档元信息**（标题、鉴权方式、外部文档链接）；接口本身由 SpringDoc 扫描
 * {@code @RestController} 自动生成，不需要在这里登记任何一个 URL ——
 * 手写登记会随代码漂移，是本目录刻意避免的做法（见 {@code docs/sql/README.md} 里
 * 「只增不改」的同源思路：让事实只有一个来源）。
 *
 * <h3>鉴权怎么试</h3>
 * 本项目的登录态是**请求头 {@code token}**（JWT，由 {@code POST /api/user/login} 返回），
 * 不是标准的 {@code Authorization: Bearer}。这里把它声明成
 * {@code apiKey / in: header / name: token}，Swagger UI 右上角「Authorize」
 * 填的就是登录返回的那一串。
 *
 * <h3>刻意不做的事</h3>
 * <ul>
 *   <li>不手写每条接口的 schema —— 由 SpringDoc 从 DTO 反射生成；</li>
 *   <li>不描述 {@code /api/chat/stream} 的帧序列 —— SSE 不是一问一答，
 *       SpringDoc 表达不了「同一条连接里按序到达的多种事件」，
 *       那部分契约在 {@code docs/sse-contract.md}（手写，权威）。</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    /** 安全方案名；与 {@code @SecurityRequirement(name = ...)} 对应 */
    public static final String SECURITY_SCHEME_TOKEN = "token";

    /**
     * 刻意写成**无参、无外部依赖**的纯构造：这样单测可以直接 new 出来断言，
     * 不必起 Spring 容器（见 {@code OpenApiConfigTest}）。
     */
    @Bean
    public OpenAPI nexusAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("nexus-agent API")
                        .version("v1")
                        .description("""
                                nexus-agent 后端接口文档。

                                **鉴权**：除登录 / 注册 / 重置密码 / 邮箱验证码外，\
                                所有接口都要在请求头带 `token`（登录接口返回的 JWT）。

                                **流式对话**（`POST /api/chat/stream`）返回 SSE，\
                                不是普通 JSON：事件信封与渲染方式见 \
                                [docs/sse-contract.md](docs/sse-contract.md)，\
                                本文档只登记它的入口参数。
                                """)
                        .license(new License().name("私有项目，不外发")))
                .externalDocs(new ExternalDocumentation()
                        .description("SSE 流式契约（手写，权威）")
                        .url("docs/sse-contract.md"))
                // 全局默认要求带 token；免鉴权接口在方法上用 @SecurityRequirements 清空
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_TOKEN))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_TOKEN,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("token")
                                .description("`POST /api/user/login` 返回的 JWT，原样放进请求头 token")));
    }
}
