package com.company.policy.mapper;

import com.company.policy.dto.UserListQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserManagementMapper {
    // 用户管理专用查询允许查看本公司停用记录；不改变登录及普通业务的停用过滤规则。
    String FILTERS =
            """
        FROM user_info u
        WHERE u.tenant_key = #{tenantKey} AND u.is_deleted IN (0, 1)
        <if test="q.roleType != null">AND u.role_type = #{q.roleType}</if>
        <if test="q.status == 'ENABLED'">AND u.is_deleted = 0</if>
        <if test="q.status == 'DISABLED'">AND u.is_deleted = 1</if>
        <if test="q.keyword != null">
          AND (LOWER(u.name) LIKE LOWER(#{q.keyword}) ESCAPE '!'
            OR LOWER(u.department) LIKE LOWER(#{q.keyword}) ESCAPE '!')
        </if>
        """;

    @Select("<script>SELECT COUNT(*) " + FILTERS + "</script>")
    long count(@Param("tenantKey") String tenantKey, @Param("q") UserListQuery query);

    @Select(
            """
        <script>
        SELECT u.id, u.name, u.avatar_url, u.department, u.role_type, u.is_deleted, u.created_at
        """
                    + FILTERS
                    + """
        ORDER BY u.created_at DESC, u.id DESC
        LIMIT #{q.pageSize} OFFSET #{offset}
        </script>
        """)
    List<UserRow> list(
            @Param("tenantKey") String tenantKey,
            @Param("q") UserListQuery query,
            @Param("offset") long offset);

    @Select(
            """
        SELECT id, name, avatar_url, department, open_id, role_type, is_deleted, created_at
        FROM user_info
        WHERE tenant_key = #{tenantKey} AND id = #{id} AND is_deleted IN (0, 1)
        """)
    DetailRow detail(@Param("tenantKey") String tenantKey, @Param("id") long id);

    // 锁定读取不可复用缓存；操作人与目标用户由服务按 ID 升序锁定。
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
        SELECT id, name, avatar_url, department, open_id, role_type, is_deleted, created_at
        FROM user_info
        WHERE tenant_key = #{tenantKey} AND id = #{id} AND is_deleted IN (0, 1)
        FOR UPDATE
        """)
    DetailRow lockUser(@Param("tenantKey") String tenantKey, @Param("id") long id);

    @Update(
            """
        UPDATE user_info SET role_type = #{roleType}, updated_at = #{now}
        WHERE tenant_key = #{tenantKey} AND id = #{id}
          AND role_type = #{expectedRoleType} AND is_deleted = #{expectedDeleted}
        """)
    int updateRole(
            @Param("tenantKey") String tenantKey,
            @Param("id") long id,
            @Param("roleType") String roleType,
            @Param("expectedRoleType") String expectedRoleType,
            @Param("expectedDeleted") int expectedDeleted,
            @Param("now") LocalDateTime now);

    @Update(
            """
        UPDATE user_info SET is_deleted = #{deleted}, updated_at = #{now}
        WHERE tenant_key = #{tenantKey} AND id = #{id}
          AND role_type = #{expectedRoleType} AND is_deleted = #{expectedDeleted}
        """)
    int updateStatus(
            @Param("tenantKey") String tenantKey,
            @Param("id") long id,
            @Param("deleted") int deleted,
            @Param("expectedRoleType") String expectedRoleType,
            @Param("expectedDeleted") int expectedDeleted,
            @Param("now") LocalDateTime now);

    record DetailRow(
            Long id,
            String name,
            String avatarUrl,
            String department,
            String openId,
            String roleType,
            int isDeleted,
            LocalDateTime createdAt) {}

    // 统计独立于列表 FILTERS，COUNT 在无匹配记录时直接返回 0。
    @Select(
            """
        SELECT COUNT(*) AS total,
          COUNT(CASE WHEN is_deleted = 0 AND role_type = 'APPLICANT' THEN 1 END) AS enabled_applicants,
          COUNT(CASE WHEN is_deleted = 0 AND role_type = 'ADMIN' THEN 1 END) AS enabled_admins,
          COUNT(CASE WHEN is_deleted = 1 THEN 1 END) AS disabled_users
        FROM user_info
        WHERE tenant_key = #{tenantKey} AND is_deleted IN (0, 1)
        """)
    StatsRow stats(@Param("tenantKey") String tenantKey);

    record StatsRow(long total, long enabledApplicants, long enabledAdmins, long disabledUsers) {}

    record UserRow(
            Long id,
            String name,
            String avatarUrl,
            String department,
            String roleType,
            int isDeleted,
            LocalDateTime createdAt) {}
}
