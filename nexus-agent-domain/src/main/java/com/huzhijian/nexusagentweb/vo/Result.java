package com.huzhijian.nexusagentweb.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class Result {
    private Integer code;   // 0成功，其他失败
    private String msg;     // 提示信息
    private Object data;         // 成功时的数据
    private Long total;

    public Result(Integer code, String msg, Object data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static Result ok(Object data){
        return new Result(0,"ok",data);
    }
    public static Result ok(){
        return ok(null);
    }
    public static Result okWithMsg(String msg){
        return new Result(0,msg,null);
    }
    public static Result ok(String msg,Object data){
        return new Result(0,msg,data);
    }
    public static Result error(String msg){
        return new Result(1,msg,null);
    }
}