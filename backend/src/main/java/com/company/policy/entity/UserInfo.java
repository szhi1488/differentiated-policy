package com.company.policy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_info")
public class UserInfo {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantKey;
    private String openId;
    private String unionId;
    private String name;
    private String department;
    private String roleType;
    private Integer isDeleted;
}
