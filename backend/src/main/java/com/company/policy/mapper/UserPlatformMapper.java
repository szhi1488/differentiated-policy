package com.company.policy.mapper;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.vo.UserPlatformOwner;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserPlatformMapper {
    @Select(
            """
            SELECT a.perspective, a.platform_code, a.user_id, u.name
            FROM user_platform_assignment a JOIN user_info u ON u.id=a.user_id AND u.tenant_key=a.tenant_key
            WHERE a.tenant_key=#{tenant} AND u.is_deleted=0 AND u.role_type='APPLICANT'
            ORDER BY a.perspective,a.platform_code
            """)
    List<UserPlatformOwner> owners(String tenant);

    @Select(
            """
            SELECT perspective, platform_code FROM user_platform_assignment
            WHERE tenant_key=#{tenant} AND user_id=#{id} ORDER BY perspective,platform_code
            """)
    List<ChannelPlatform> assigned(String tenant, long id);

    @Select(
            """
            SELECT id FROM user_info WHERE tenant_key=#{tenant} AND id=#{id}
              AND role_type='APPLICANT' AND is_deleted=0 FOR UPDATE
            """)
    Long lockApplicant(String tenant, long id);

    @Delete("DELETE FROM user_platform_assignment WHERE tenant_key=#{tenant} AND user_id=#{id}")
    void release(String tenant, long id);

    @Insert(
            """
            INSERT INTO user_platform_assignment (tenant_key, user_id, perspective, platform_code)
            VALUES (#{tenant},#{id},#{scope.perspective},#{scope.platformCode})
            """)
    void assign(String tenant, long id, ChannelPlatform scope);
}
