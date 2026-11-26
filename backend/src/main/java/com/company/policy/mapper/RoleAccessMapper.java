package com.company.policy.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RoleAccessMapper {
    String PERMISSION_START =
            """
        EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.id=ur.role_id AND r.tenant_key=ur.tenant_key AND r.is_enabled=1
         WHERE ur.user_id=u.id AND ur.tenant_key=u.tenant_key AND (r.role_code='ADMIN'
         OR EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.tenant_key=r.tenant_key AND rp.role_id=r.id AND rp.permission_code=
        """;
    String PERMISSION_END = "))) ";
    String PERMISSION_FOR_USER = PERMISSION_START + "#{permission}" + PERMISSION_END;
    String CAN_SUBMIT = PERMISSION_START + "'policy.apply'" + PERMISSION_END;

    @Select(
            "SELECT COUNT(*) FROM user_info u WHERE u.id=#{userId} AND u.tenant_key=#{tenant} AND u.is_deleted=0 AND "
                    + PERMISSION_FOR_USER)
    int hasPermission(String tenant, long userId, String permission);

    @Select(
            """
        SELECT b.name FROM policy_review_brand_owner o JOIN brand b ON b.id=o.brand_id
        WHERE o.tenant_key=#{tenant} AND o.purchase_user_id=#{userId} ORDER BY b.id
        """)
    List<String> purchaseBrandNames(String tenant, long userId);

    @Select(
            """
        SELECT r.role_code FROM sys_role r JOIN sys_user_role a ON a.role_id=r.id AND a.tenant_key=r.tenant_key
        WHERE a.tenant_key=#{tenant} AND a.user_id=#{id} AND (#{allRoles}=TRUE OR r.is_enabled=1) ORDER BY r.role_code
        """)
    List<String> userRoles(String tenant, long id, boolean allRoles);

    @Select(
            """
        <script>
        SELECT ur.user_id, r.role_code FROM sys_user_role ur
        JOIN sys_role r ON r.id=ur.role_id AND r.tenant_key=ur.tenant_key
        WHERE ur.tenant_key=#{tenant} AND ur.user_id IN
        <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
        ORDER BY ur.user_id,r.role_code
        </script>
        """)
    List<UserRole> rolesForUsers(String tenant, List<Long> ids);

    record UserRole(long userId, String roleCode) {}

    @Select(
            """
        SELECT DISTINCT p.permission_code FROM sys_user_role a JOIN sys_role r ON r.id=a.role_id AND r.tenant_key=a.tenant_key
        JOIN sys_role_permission p ON p.role_id=r.id AND p.tenant_key=r.tenant_key WHERE a.tenant_key=#{tenant} AND a.user_id=#{id}
        AND r.is_enabled=1 ORDER BY p.permission_code
        """)
    List<String> userPermissions(String tenant, long id);

    @Select(
            "SELECT id,role_code,role_name,is_builtin,is_enabled,version FROM sys_role WHERE tenant_key=#{tenant} ORDER BY id")
    List<RoleRow> roles(String tenant);

    @Select(
            "SELECT id,role_code,role_name,is_builtin,is_enabled,version FROM sys_role WHERE tenant_key=#{tenant} AND id=#{id} FOR UPDATE")
    RoleRow lock(String tenant, long id);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM sys_role WHERE tenant_key=#{tenant} AND role_code='ADMIN' FOR UPDATE")
    Long lockAdministration(String tenant);

    @Select("SELECT id FROM sys_role WHERE tenant_key=#{tenant} AND role_code=#{code}")
    Long roleId(String tenant, String code);

    @Insert(
            "INSERT INTO sys_role(tenant_key,role_code,role_name,is_builtin,is_enabled,version) VALUES(#{tenant},#{code},#{name},#{builtin},#{enabled},1)")
    void insertRole(String tenant, String code, String name, boolean builtin, boolean enabled);

    @Insert(
            "INSERT INTO sys_role(tenant_key,role_code,role_name,is_builtin,is_enabled,version) VALUES(#{tenant},'ADMIN','系统管理员',1,1,1) ON DUPLICATE KEY UPDATE role_code=VALUES(role_code)")
    void ensureAdministration(String tenant);

    @Update(
            "UPDATE sys_role SET role_name=#{name},is_enabled=#{enabled},version=version+1 WHERE tenant_key=#{tenant} AND id=#{id} AND version=#{version}")
    int updateRole(String tenant, long id, String name, boolean enabled, int version);

    @Select(
            "SELECT permission_code FROM sys_role_permission WHERE tenant_key=#{tenant} AND role_id=#{id} ORDER BY permission_code")
    List<String> permissions(String tenant, long id);

    @Delete("DELETE FROM sys_role_permission WHERE tenant_key=#{tenant} AND role_id=#{id}")
    void clearPermissions(String tenant, long id);

    @Insert(
            "INSERT INTO sys_role_permission(tenant_key,role_id,permission_code) VALUES(#{tenant},#{id},#{code})")
    void grantPermission(String tenant, long id, String code);

    @Insert(
            "INSERT INTO sys_user_role(tenant_key,user_id,role_id) VALUES(#{tenant},#{id},#{roleId})")
    void grantRole(String tenant, long id, long roleId);

    @Delete("DELETE FROM sys_user_role WHERE tenant_key=#{tenant} AND user_id=#{id}")
    void removeRoles(String tenant, long id);

    @Select(
            "SELECT user_id FROM sys_user_role WHERE tenant_key=#{tenant} AND role_id=#{roleId} ORDER BY user_id")
    List<Long> assignedUsers(String tenant, long roleId);

    record RoleRow(
            long id,
            String roleCode,
            String roleName,
            boolean isBuiltin,
            boolean isEnabled,
            int version) {}
}
