package com.huzhijian.nexusagentweb;

import cn.hutool.json.JSONUtil;
import com.huzhijian.nexusagentweb.lexiang.LexiangApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 乐享接口响应解析的测试。
 * <p>
 * 钉的是 2026-10-03 联调时暴露的那个坑：<b>团队列表与知识库列表虽然都用 JSON:API 外壳，
 * 但 attributes 里的字段完全不同</b>。用错模型会导致「接口 200 但列表空」这种
 * 静默失败 —— 不报错，只给用户一个空下拉框，极难定位。
 * <p>
 * 本测试直接拿官方文档里的真实响应片段做断言。
 */
class LexiangResponseParseTest {

    @Test
    @DisplayName("团队列表：名称与 code 在 attributes 下（不是顶层）")
    void parsesTeamAttributes() {
        // 取自官方「获取团队列表」文档的响应示例
        String raw = """
                {
                  "data": [
                    {
                      "type": "team",
                      "id": "e6f68cb41fad11ea91d40242c0a83004",
                      "attributes": {
                        "name": "研发中心",
                        "code": "k100022",
                        "is_secret": 1,
                        "created_at": "2016-06-30 10:43:55"
                      }
                    }
                  ],
                  "meta": { "page_token": null }
                }
                """;

        var envelope = JSONUtil.toBean(raw, new cn.hutool.core.lang.TypeReference<
                LexiangApi.Envelope<List<LexiangApi.TeamNode>>>() {
        }, true);

        assertNotNull(envelope.getData());
        assertEquals(1, envelope.getData().size());
        LexiangApi.TeamNode team = envelope.getData().get(0);
        assertEquals("e6f68cb41fad11ea91d40242c0a83004", team.getId());
        // 这两个断言是重点：曾经用 SpaceNode 解析，导致这里全是 null
        assertEquals("研发中心", team.getAttributes().getName());
        assertEquals("k100022", team.getAttributes().getCode());
    }

    @Test
    @DisplayName("知识库列表：名称与 logo 在 attributes 下")
    void parsesSpaceAttributes() {
        String raw = """
                {
                  "data": [
                    {
                      "type": "kb_space",
                      "id": "12a0fcf8781011f099de2688xxxxxxb",
                      "attributes": { "name": "产品需求库", "logo": "https://x/logo.png" }
                    }
                  ]
                }
                """;

        var envelope = JSONUtil.toBean(raw, new cn.hutool.core.lang.TypeReference<
                LexiangApi.Envelope<List<LexiangApi.SpaceNode>>>() {
        }, true);

        assertEquals(1, envelope.getData().size());
        assertEquals("产品需求库", envelope.getData().get(0).getAttributes().getName());
        assertEquals("https://x/logo.png", envelope.getData().get(0).getAttributes().getLogo());
    }

    @Test
    @DisplayName("检索结果：content 片段与 url 能正确取出（供引用溯源）")
    void parsesSearchHit() {
        String raw = """
                {
                  "code": 0,
                  "message": "success",
                  "data": {
                    "list": [
                      {
                        "title": "员工手册",
                        "content": "年假需提前 3 天申请",
                        "url": "https://lexiangla.com/pages/abc",
                        "score": 0.92
                      }
                    ]
                  }
                }
                """;

        var envelope = JSONUtil.toBean(raw, new cn.hutool.core.lang.TypeReference<
                LexiangApi.Envelope<LexiangApi.SearchData>>() {
        }, true);

        assertEquals(0, envelope.getCode());
        LexiangApi.SearchHit hit = envelope.getData().getList().get(0);
        assertEquals("员工手册", hit.getTitle());
        assertEquals("年假需提前 3 天申请", hit.getContent());
        assertEquals("https://lexiangla.com/pages/abc", hit.getUrl());
    }
}
