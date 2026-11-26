package com.company.policy.mapper;

import com.company.policy.dto.UserListQuery;
import com.company.policy.vo.PlatformTeam;
import com.company.policy.vo.PolicyReviewerCandidate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserManagementMapper {
    String FILTERS =
            """
        FROM user_info u WHERE u.tenant_key=#{tenantKey} AND u.is_deleted IN(0,1)
        <if test="q.roleCode!=null">AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON ur.role_id=r.id AND ur.tenant_key=r.tenant_key WHERE ur.tenant_key=u.tenant_key AND ur.user_id=u.id AND r.role_code=#{q.roleCode})</if>
        <if test="q.permission!=null">AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON ur.role_id=r.id AND ur.tenant_key=r.tenant_key AND r.is_enabled=1
          WHERE ur.tenant_key=u.tenant_key AND ur.user_id=u.id AND (r.role_code='ADMIN' OR EXISTS(SELECT 1 FROM sys_role_permission p WHERE p.tenant_key=r.tenant_key AND p.role_id=r.id AND (p.permission_code=#{q.permission} OR (#{q.permission}='PLATFORM_MEMBER' AND p.permission_code IN ('policy.apply','policy.read.platform_submitted','subsidy.read.platform'))))))</if>
        <if test="q.status=='ENABLED'">AND u.is_deleted=0</if>
        <if test="q.status=='DISABLED'">AND u.is_deleted=1</if>
        <if test="q.name!=null">AND LOWER(u.name) LIKE LOWER(#{q.name}) ESCAPE '!'</if>
        <if test="q.department!=null">AND LOWER(u.department) LIKE LOWER(#{q.department}) ESCAPE '!'</if>
        <if test="q.keyword!=null">AND (LOWER(u.name) LIKE LOWER(#{q.keyword}) ESCAPE '!' OR LOWER(u.department) LIKE LOWER(#{q.keyword}) ESCAPE '!')</if>
        """;
    String FIELDS =
            "u.id,u.name,u.avatar_url,u.department,u.open_id,u.auth_version,u.is_deleted,u.created_at ";

    @Select("<script>SELECT COUNT(*) " + FILTERS + "</script>")
    long count(String tenantKey, UserListQuery q);

    @Select(
            "<script>SELECT "
                    + FIELDS
                    + FILTERS
                    + " ORDER BY u.created_at DESC,u.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<DetailRow> list(String tenantKey, UserListQuery q, long offset);

    String REVIEW_FILTERS = FILTERS + " AND " + ReviewPermissions.FOR_GROUP;

    @Select("<script>SELECT COUNT(*) " + REVIEW_FILTERS + "</script>")
    long countReviewCandidates(String tenantKey, UserListQuery q, String group);

    @Select(
            "<script>SELECT u.id,u.name,u.department "
                    + REVIEW_FILTERS
                    + " ORDER BY u.created_at DESC,u.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PolicyReviewerCandidate> reviewCandidates(
            String tenantKey, UserListQuery q, String group, long offset);

    @Select(
            "<script>SELECT u.id,u.name,u.department,"
                    + RoleAccessMapper.CAN_SUBMIT
                    + " AS can_submit "
                    + FILTERS
                    + " ORDER BY u.created_at DESC,u.id DESC LIMIT #{q.pageSize} OFFSET #{offset}</script>")
    List<PlatformTeam.Candidate> platformCandidates(String tenantKey, UserListQuery q, long offset);

    @Select("SELECT " + FIELDS + "FROM user_info u WHERE u.tenant_key=#{tenantKey} AND u.id=#{id}")
    DetailRow detail(String tenantKey, long id);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            "SELECT "
                    + FIELDS
                    + "FROM user_info u WHERE u.tenant_key=#{tenantKey} AND u.id=#{id} FOR UPDATE")
    DetailRow lockUser(String tenantKey, long id);

    @Update(
            "UPDATE user_info SET is_deleted=#{deleted},auth_version=auth_version+1,updated_at=#{now} WHERE tenant_key=#{tenantKey} AND id=#{id} AND auth_version=#{version}")
    int change(String tenantKey, long id, int deleted, int version, LocalDateTime now);

    @Select(
            """
        SELECT COUNT(*) AS total,COUNT(CASE WHEN u.is_deleted=0 THEN 1 END) AS enabled_users,
        COUNT(CASE WHEN u.is_deleted=0 AND EXISTS (SELECT 1 FROM sys_user_role a JOIN sys_role r ON r.id=a.role_id AND r.tenant_key=a.tenant_key
         WHERE a.user_id=u.id AND a.tenant_key=u.tenant_key AND r.role_code='ADMIN' AND r.is_enabled=1) THEN 1 END) AS enabled_admins,
        COUNT(CASE WHEN u.is_deleted=1 THEN 1 END) AS disabled_users FROM user_info u WHERE u.tenant_key=#{tenantKey}
        """)
    StatsRow stats(String tenantKey);

    record StatsRow(long total, long enabledUsers, long enabledAdmins, long disabledUsers) {}

    record DetailRow(
            Long id,
            String name,
            String avatarUrl,
            String department,
            String openId,
            int authVersion,
            int isDeleted,
            LocalDateTime createdAt) {}
}
