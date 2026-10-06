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

    /**
     * 失败，**并带上结构化数据**（2026-10-06）。
     * <p>
     * 动机：前端要求「上传被拒时把当时的文件配额一起返回」——
     * 只有一句文案的话，前端只能弹个 toast 然后让用户「再试一次」，
     * 可他根本不知道自己离上限还有多远；下次直接撞墙时才告知，体验上等于「突然不让用了」。
     * 有了这份快照就能提前禁掉上传按钮、显示「还能传 N 个」。
     * <p>
     * ⚠️ 注意与 {@link #ok(Object)} 的<b>参数顺序相反</b>：
     * {@code ok(data)} 是数据在前，{@code error(msg, data)} 是 msg 在前。
     * 另：{@code data} 为 null 时与 {@link #error(String)} 完全等价（向前兼容）。
     */
    public static Result error(String msg, Object data){
        return new Result(1,msg,data);
    }
}