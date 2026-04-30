package com.huzhijian.nexusagentweb.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/27
 * 说明:
 */
@Data
@AllArgsConstructor
public class APIConfig {
//    如果是添加系统自动生成，如果是更新必须要有！
    private String id;
    @NotBlank(message = "取一个名字吧！")
    private String name;
    @NotBlank(message = "key不能为空！")
    private String APIKey;
    @NotBlank(message = "base_url不能为空！")
    private String baseUrl;
//  模型列表，存储mx ID
    @NotEmpty(message = "模型列表为空")
    private List<String> model;
//    是否默认
    private Boolean isDefault;
}
