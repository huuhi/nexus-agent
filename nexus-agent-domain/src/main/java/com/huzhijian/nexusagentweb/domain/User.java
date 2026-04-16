package com.huzhijian.nexusagentweb.domain;



import java.io.Serializable;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
* 用户表
* @TableName user
*/
@Data
@TableName(value ="user")
public class User implements Serializable {

    /**
    * 
    */
    @NotNull(message="[]不能为空")
    private Long id;
    /**
    * 邮箱，唯一
    */
    @Size(max= 255,message="编码长度不能超过255")
    @Length(max= 255,message="编码长度不能超过255")
    private String email;
    /**
    * 
    */
    @NotBlank(message="[]不能为空")
    @Size(max= 255,message="编码长度不能超过255")
    @Length(max= 255,message="编码长度不能超过255")
    private String username;
    /**
    * 使用bcrypt算法加密
    */
    private String password;
    /**
    * 
    */
    @Size(max= 255,message="编码长度不能超过255")
    @Length(max= 255,message="编码长度不能超过255")
    private String avatarImg;
    /**
    *
    */
    private Date registerTime;
    /**
    * API限制，每个用户只能使用100次
    */
    @NotNull(message="[API限制，每个用户只能使用100次]不能为空")
    private Integer apiQuota;
    /**
    * githubID
    */
    @Size(max= 255,message="编码长度不能超过255")
    @Length(max= 255,message="编码长度不能超过255")
    private String githubId;

}
