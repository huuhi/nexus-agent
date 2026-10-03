package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 乐享知识库（space）
 */
@Data
@Builder
public class LexiangSpaceVO {
    private String id;
    private String name;
    private String logo;
    /** 所属团队 id */
    private String teamId;
    /** 该知识库的访问链接（用于前端跳转回乐享） */
    private String url;
}
