package com.huzhijian.nexusagentweb.vo;

import lombok.Data;

import java.util.Date;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/6/21
 * 说明:
 */
@Data
public class UserMemoryVO {
    private Long id;


    /**
     *
     */
    private String content;
    private String source;

    /**
     *
     */
    private Date createAt;
}
