package com.huzhijian.nexusagentweb.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/5/9
 * 说明:
 */
@Data
@AllArgsConstructor
public class Memories {
    private List<UserLongMemory> add;
    private List<UserLongMemory> update;
    private List<String> delete;
}
