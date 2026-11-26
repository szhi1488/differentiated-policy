package com.company.policy.mapper;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.vo.UserPlatformOwner;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserPlatformMapper {
    String SCOPE =
            " tenant_key=#{tenant} AND perspective=#{scope.perspective} AND platform_code=#{scope.platformCode} ";

    @Insert(
            "INSERT INTO platform_team (tenant_key,perspective,platform_code) VALUES(#{tenant},#{scope.perspective},#{scope.platformCode}) ON DUPLICATE KEY UPDATE platform_code=VALUES(platform_code)")
    void ensureTeam(String tenant, ChannelPlatform scope);

    @Select(
            "SELECT perspective,platform_code,version FROM platform_team WHERE tenant_key=#{tenant} ORDER BY perspective,platform_code")
    List<TeamRow> teams(String tenant);

    record TeamRow(String perspective, String platformCode, int version) {}

    @Select(
            """
        SELECT a.perspective,a.platform_code,a.user_id,u.name,a.member_kind,
        """
                    + RoleAccessMapper.CAN_SUBMIT
                    + """
         AS can_submit FROM user_platform_assignment a
        JOIN user_info u ON u.id=a.user_id AND u.tenant_key=a.tenant_key AND u.is_deleted=0
        WHERE a.tenant_key=#{tenant} ORDER BY a.perspective,a.platform_code,a.user_id
        """)
    List<UserPlatformOwner> owners(String tenant);

    @Select(
            "SELECT perspective,platform_code FROM user_platform_assignment WHERE tenant_key=#{tenant} AND user_id=#{id} AND member_kind='SUBMITTER' ORDER BY perspective,platform_code")
    List<ChannelPlatform> assigned(String tenant, long id);

    @Select(
            "SELECT perspective,platform_code FROM user_platform_assignment WHERE tenant_key=#{tenant} AND user_id=#{id} ORDER BY perspective,platform_code")
    List<ChannelPlatform> memberships(String tenant, long id);

    @Select(
            """
        SELECT t.version FROM platform_team t
        WHERE t.tenant_key=#{tenant} AND EXISTS (
          SELECT 1 FROM user_platform_assignment m JOIN sys_user_role ur ON ur.user_id=m.user_id AND ur.tenant_key=m.tenant_key
          WHERE m.tenant_key=t.tenant_key AND m.perspective=t.perspective AND m.platform_code=t.platform_code AND ur.role_id=#{roleId})
        ORDER BY t.perspective,t.platform_code FOR UPDATE
        """)
    List<Integer> lockAssignedTeams(String tenant, long roleId);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            "SELECT id FROM user_info WHERE tenant_key=#{tenant} AND id=#{id} AND is_deleted=0 FOR UPDATE")
    Long lockActiveAccount(String tenant, long id);

    @Select(
            "SELECT u.id FROM user_info u WHERE u.tenant_key=#{tenant} AND u.id=#{id} AND u.is_deleted=0 AND "
                    + RoleAccessMapper.PERMISSION_FOR_USER)
    Long permittedAccount(String tenant, long id, String permission);

    /** 先等待账号写锁，再读取当前岗位。平台交接与岗位撤权都与提交事务互斥。 */
    default Long lockApplicant(String tenant, long id) {
        return lockActiveAccount(tenant, id) == null
                ? null
                : permittedAccount(tenant, id, "policy.apply");
    }

    @Select("SELECT version FROM platform_team WHERE " + SCOPE + " FOR UPDATE")
    Integer lockTeam(String tenant, ChannelPlatform scope);

    @Select(
            "SELECT user_id,member_kind FROM user_platform_assignment WHERE "
                    + SCOPE
                    + " ORDER BY user_id")
    List<MemberRow> teamMembers(String tenant, ChannelPlatform scope);

    record MemberRow(long userId, String memberKind) {}

    @Select(
            "SELECT member_kind FROM user_platform_assignment WHERE "
                    + SCOPE
                    + " AND user_id=#{id}")
    String kind(String tenant, ChannelPlatform scope, long id);

    @Delete("DELETE FROM user_platform_assignment WHERE " + SCOPE + " AND user_id=#{id}")
    void remove(String tenant, ChannelPlatform scope, long id);

    @Update(
            "UPDATE user_platform_assignment SET member_kind='MEMBER' WHERE "
                    + SCOPE
                    + " AND user_id=#{id}")
    void demote(String tenant, ChannelPlatform scope, long id);

    @Delete("DELETE FROM user_platform_assignment WHERE " + SCOPE)
    void clearTeam(String tenant, ChannelPlatform scope);

    @Insert(
            "INSERT INTO user_platform_assignment(tenant_key,perspective,platform_code,user_id,member_kind) VALUES(#{tenant},#{scope.perspective},#{scope.platformCode},#{id},#{kind})")
    void assign(String tenant, long id, ChannelPlatform scope, String kind);

    @Update("UPDATE platform_team SET version=version+1,updated_by=#{actor} WHERE " + SCOPE)
    void advance(String tenant, ChannelPlatform scope, long actor);
}
