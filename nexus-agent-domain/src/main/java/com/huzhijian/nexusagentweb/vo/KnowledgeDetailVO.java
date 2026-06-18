package com.huzhijian.nexusagentweb.vo;

import lombok.Data;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/28
 * 说明:
 */
@Data
public class KnowledgeDetailVO {
    private Integer id;

    /**
     *
     */
    private String name;

    /**
     *
     */
    private String describe;

    /**
     *
     */
    private Boolean isPublic;

    /**
     *
     */
    private String languageCode;

    private List<KnowledgeFileVO>  knowledgeBaseFileList;
}
