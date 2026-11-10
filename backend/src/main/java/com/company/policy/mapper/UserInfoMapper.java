package com.company.policy.mapper;

import com.company.policy.entity.UserInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserInfoMapper {
    // 租户与 open_id 的联合唯一键防止并发首次登录创建重复用户。
    // 登录时不得覆盖已有角色、部门或逻辑删除标记。
    @Insert(
            """
        INSERT INTO user_info (tenant_key, open_id, union_id, name, role_type, is_deleted)
        VALUES (#{tenantKey}, #{openId}, #{unionId}, #{name}, 'APPLICANT', 0)
        ON DUPLICATE KEY UPDATE
          name = CASE WHEN is_deleted = 0 THEN VALUES(name) ELSE name END,
          union_id = CASE WHEN is_deleted = 0 THEN COALESCE(VALUES(union_id), union_id) ELSE union_id END
        """)
    void upsertVerifiedIdentity(UserInfo user);

    @Select(
            """
        SELECT id, tenant_key, open_id, union_id, name, department, role_type, is_deleted
        FROM user_info WHERE tenant_key = #{tenantKey} AND open_id = #{openId}
        """)
    UserInfo findIdentity(@Param("tenantKey") String tenantKey, @Param("openId") String openId);
}
