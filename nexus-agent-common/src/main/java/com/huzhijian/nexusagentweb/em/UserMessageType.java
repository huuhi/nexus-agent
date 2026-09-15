package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明: 用户消息类型，文本，文件，图片
 */
public enum UserMessageType {
    TEXT("TEXT"),
    FILE("FILE"),
    IMAGE("IMAGE");
    @Getter
    private final String value;
    UserMessageType(String value){
        this.value=value;
    }
}
