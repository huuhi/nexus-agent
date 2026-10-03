package com.huzhijian.nexusagentweb.lexiang;

import lombok.Builder;
import lombok.Data;

/**
 * 乐享开放接口的常量与轻量请求/响应模型。
 * <p>
 * 只覆盖「检索」所需的部分 —— 本项目的接入范围<b>只有检索</b>，
 * 不做文件上传、不做 AI 问答（问答的 token 配额与模型耦合度高，且用户明确不需要）。
 */
public final class LexiangApi {

    private LexiangApi() {
    }

    /** 开放接口域名 */
    public static final String BASE = "https://lxapi.lexiangla.com";

    /** 换取 access_token */
    public static final String PATH_TOKEN = "/cgi-bin/token";

    /** 团队列表 */
    public static final String PATH_TEAMS = "/cgi-bin/v1/kb/teams";

    /** 知识库列表（必须带 team_id） */
    public static final String PATH_SPACES = "/cgi-bin/v1/kb/spaces";

    /** AI 检索（纯检索，不含 LLM 生成） */
    public static final String PATH_SEARCH = "/cgi-bin/v1/ai/search";

    /**
     * 匿名 / 系统账号。
     * <p>
     * 用它发起检索时，乐享<b>只返回全公司成员均有权限的公开知识</b>。
     * 用户希望检索自己的私有知识时必须填自己的成员账号（x-staff-id）。
     */
    public static final String STAFF_SYSTEM_BOT = "system-bot";

    /**
     * access_token 有效期只有 2 小时，提前 5 分钟判定过期。
     * <p>
     * 留 5 分钟余量是为了避免"token 还没到过期时间，但已经不能再用了"的边界抖动 ——
     * 乐享取 token 的接口<b>限频 20 次 / 10 分钟</b>，撞了就得等。
     */
    public static final long TOKEN_TTL_SECONDS = 7200L - 300L;

    /**
     * POST /cgi-bin/token 的响应。
     */
    @Data
    public static class TokenResp {
        /** token_type */
        private String token_type;
        /** 有效期（秒），乐享返回 7200 */
        private Long expires_in;
        /** 访问令牌 */
        private String access_token;
    }

    /**
     * 乐享统一响应信封。code == 0 表示成功。
     */
    @Data
    public static class Envelope<T> {
        private Integer code;
        private String message;
        private String request_id;
        private T data;
    }

    /**
     * POST /cgi-bin/v1/ai/search 的 data 部分。
     */
    @Data
    public static class SearchData {
        private java.util.List<SearchHit> list;
    }

    /**
     * 一条检索命中片段。
     */
    @Data
    public static class SearchHit {
        /** 文档标题 */
        private String title;
        /** 命中的正文片段（markdown 格式），这正是我们要交给模型的内容 */
        private String content;
        /** 乐享上的文档链接，可用于引用溯源 */
        private String url;
        /** 相关性分数，仅在 with_score=true 时返回 */
        private Double score;
    }

    /**
     * GET /cgi-bin/v1/kb/spaces 的一个 space 节点。
     * <p>
     * 乐享的列表接口走 JSON:API 规范，字段在 attributes 下。
     */
    @Data
    public static class SpaceNode {
        private String type;
        private String id;
        private SpaceAttributes attributes;
        private SpaceRelationships relationships;

        @Data
        public static class SpaceAttributes {
            private String name;
            private String logo;
        }

        @Data
        public static class SpaceRelationships {
            private Rel team;
            private Rel root_entry;

            @Data
            public static class Rel {
                private DataRef data;
            }

            @Data
            public static class DataRef {
                private String type;
                private String id;
            }
        }
    }

    /**
     * 检索范围（对应 ai/search 的 targets）。
     */
    @Data
    @Builder
    public static class SearchTarget {
        /** space=知识库 / team=团队空间 / kb_entry=知识节点 */
        private String type;
        private String id;

        public static SearchTarget space(String spaceId) {
            return SearchTarget.builder().type("space").id(spaceId).build();
        }
    }
}
