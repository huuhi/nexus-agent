package com.huzhijian.nexusagentweb;

import com.huzhijian.nexusagentweb.config.OpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 文档配置（P3-4）的单元测试。
 * <p>
 * 只断言**手写**的那部分（标题、鉴权方式、外部文档链接）——
 * 接口清单是 SpringDoc 扫描出来的，不在这里断言（那属于集成测试范畴）。
 * <p>
 * 之所以值得测：鉴权方式写错（比如把 header 名写成 `Authorization`）
 * 不会有任何编译错误，只会导致**每个用 Swagger 试接口的人都 401**，
 * 而且很难联想到是文档配置的问题。
 */
class OpenApiConfigTest {

    private final OpenAPI api = new OpenApiConfig().nexusAgentOpenApi();

    @Test
    @DisplayName("文档有标题与版本，不会在 Swagger UI 里显示成 anonymous")
    void hasTitleAndVersion() {
        assertNotNull(api.getInfo());
        assertEquals("nexus-agent API", api.getInfo().getTitle());
        assertNotNull(api.getInfo().getVersion());
    }

    @Test
    @DisplayName("鉴权方式 = 请求头 token（本项目不是 Bearer，写错会全体 401）")
    void securitySchemeIsTokenHeader() {
        SecurityScheme scheme = api.getComponents().getSecuritySchemes()
                .get(OpenApiConfig.SECURITY_SCHEME_TOKEN);

        assertNotNull(scheme);
        assertEquals(SecurityScheme.Type.APIKEY, scheme.getType());
        assertEquals(SecurityScheme.In.HEADER, scheme.getIn());
        assertEquals("token", scheme.getName(), "本项目登录态在请求头 token，不是 Authorization");
    }

    @Test
    @DisplayName("全局默认要求带 token（免鉴权接口在方法上用 @SecurityRequirements 清空）")
    void globalSecurityRequirement() {
        assertTrue(api.getSecurity().stream()
                .anyMatch(req -> req.containsKey(OpenApiConfig.SECURITY_SCHEME_TOKEN)));
    }

    @Test
    @DisplayName("文档里明确指向手写的 SSE 契约（否则有人会以为 stream 返回普通 JSON）")
    void pointsToSseContract() {
        assertTrue(api.getInfo().getDescription().contains("docs/sse-contract.md"),
                "SSE 是 SpringDoc 表达不了的部分，必须在文档正文里把人引到手写契约");
        assertNotNull(api.getExternalDocs());
        assertTrue(api.getExternalDocs().getUrl().contains("docs/sse-contract.md"));
    }
}
