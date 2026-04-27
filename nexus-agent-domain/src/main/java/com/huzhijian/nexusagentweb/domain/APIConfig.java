package com.huzhijian.nexusagentweb.domain;

import jakarta.validation.constraints.NotBlank;
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
    @NotBlank
    private String token;
    @NotBlank
    private String baseUrl;
//  模型列表，存储mx ID
    private List<String> model;
}
