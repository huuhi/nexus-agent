package com.huzhijian.nexusagentweb.handler;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * @author 胡志坚
 * @version 1.0
 * 创造日期 2026/6/16
 * 说明:
 */
@Component
public class SafeExecuteToolHandler {

    public  Map<String,Object> mapTool(Supplier<Map<String,Object>> supplier){
        try {
            return supplier.get();
        }catch (Exception e){
            return Map.of("error",e.getMessage(),"success",false);
        }
    }
    public  String StringTool(Supplier<String> supplier){
        try {
            return supplier.get();
        }catch (Exception e){
            return e.getMessage();
        }
    }
    public List<Map<String,Object>> ListTool(Supplier<List<Map<String,Object>>> supplier){
        try {
            return supplier.get();
        }catch (Exception e){
            return List.of(Map.of("error",e.getMessage(),"success",false));
        }
    }
}
