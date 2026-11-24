package com.company.policy.mapper;

import com.company.policy.entity.UserInfo;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyReviewWorkflowMapper {
    String ROUND_JOIN =
            " LEFT JOIN policy_review_round r ON r.policy_id=a.policy_id AND r.tenant_key=a.tenant_key AND r.version=a.version ";
    // 待审阶段取自本轮冻结的节点。
    String CURRENT_STAGE = " CASE WHEN a.audit_status='SUBMITTED' THEN r.stage END ";

    @Insert(
            """
            INSERT INTO policy_review_round
                (policy_id, version, tenant_key, settings_version, stage, route_json, created_at)
            VALUES (#{id}, #{version}, #{tenant}, #{settingsVersion}, #{stage}, #{route}, #{at})
            """)
    int insertRound(
            String tenant,
            long id,
            int version,
            int settingsVersion,
            String stage,
            String route,
            LocalDateTime at);

    @Insert(
            """
            <script>INSERT INTO policy_review_member
                (policy_id, version, tenant_key, group_code, user_id, display_name, department)
            VALUES <foreach collection="users" item="u" separator=",">
                (#{id}, #{version}, #{tenant}, #{group}, #{u.id}, #{u.name}, #{u.department})
            </foreach></script>
            """)
    int insertMembers(String tenant, long id, int version, String group, List<UserInfo> users);

    record Member(long id, String groupCode, long userId, String memberStatus) {}

    @Select(
            """
            SELECT id, group_code, user_id, member_status FROM policy_review_member
            WHERE tenant_key=#{tenant} AND policy_id=#{id} AND version=#{version}
            ORDER BY group_code, user_id, assignment_no
            """)
    List<Member> members(String tenant, long id, int version);

    @Select(
            """
            SELECT id FROM policy_review_member
            WHERE tenant_key=#{tenant} AND policy_id=#{id} AND version=#{version}
              AND group_code='REVISION' AND user_id=#{owner} AND member_status='ACTIVE' FOR UPDATE
            """)
    Long activeRevision(String tenant, long id, int version, long owner);

    @Update(
            """
            UPDATE policy_review_member SET member_status='HANDLED'
            WHERE tenant_key=#{tenant} AND policy_id=#{id} AND version=#{version} AND id=#{memberId}
              AND group_code='REVISION' AND user_id=#{owner} AND member_status='ACTIVE'
            """)
    int finishRevision(String tenant, long id, int version, long memberId, long owner);

    @Select(
            """
            SELECT applicant_id FROM policy_apply
            WHERE tenant_key=#{tenant} AND id=#{id} AND is_deleted=0
            """)
    long applicantId(String tenant, long id);

    @Select(
            """
            SELECT COUNT(*) FROM policy_review_delivery
            WHERE tenant_key=#{tenant} AND policy_id=#{id} AND version=#{version}
              AND event_key=#{event}
            """)
    int eventRegistered(String tenant, long id, int version, String event);

    // 只接受业务唯一键冲突时的幂等重放，其他约束错误必须回滚原业务事务。
    @Insert(
            """
            INSERT INTO policy_review_delivery
                (tenant_key, policy_id, version, event_key, action_type, recipient_key, payload_json, created_at)
            VALUES (#{tenant}, #{id}, #{version}, #{event}, #{action}, #{recipient}, #{payload}, #{at})
            ON DUPLICATE KEY UPDATE id=id
            """)
    void register(
            String tenant,
            long id,
            int version,
            String event,
            String action,
            long recipient,
            String payload,
            LocalDateTime at);
}
