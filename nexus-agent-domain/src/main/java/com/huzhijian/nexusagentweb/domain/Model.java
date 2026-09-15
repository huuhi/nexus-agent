package com.huzhijian.nexusagentweb.domain;

import com.huzhijian.nexusagentweb.em.ModelType;
import lombok.Data;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/6/18
 * 说明:
 */
@Data
public class Model {
    private String name;
    private ModelType type;
}
