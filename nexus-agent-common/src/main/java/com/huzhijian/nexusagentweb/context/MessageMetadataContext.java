package com.huzhijian.nexusagentweb.context;

import java.util.Map;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/4/19
 * 说明: 存储文件元数据
 */
public class MessageMetadataContext {
    private static final ThreadLocal<Map<String,Object>> METADATA_CONTEXT=new ThreadLocal<>();

    public static void set(Map<String,Object> metadata){
        METADATA_CONTEXT.set(metadata);
    }
    public static Map<String,Object> get(){
        return METADATA_CONTEXT.get();
    }
    public static void clear(){
        METADATA_CONTEXT.remove();
    }
}
