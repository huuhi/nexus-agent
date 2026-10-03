package com.huzhijian.nexusagentweb.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 乐享团队（团队空间）
 */
@Data
@Builder
public class LexiangTeamVO {
    private String id;
    private String name;
    /** 团队 code（形如 k100022），便于用户辨认哪个是自己的团队 */
    private String code;
}
