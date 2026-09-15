package com.huzhijian.nexusagentweb.em;

import lombok.Getter;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/17
 * 说明:
 */
public enum UploadStatus {
//    'FAILED', 'SUCCESS', 'PROCESSING'
    FAILED("FAILED"),
    SUCCESS("SUCCESS"),
    PROCESSING("PROCESSING");
    @Getter
    private final String value;
    UploadStatus(String value){
        this.value=value;
    }
}
