package com.company.policy.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyReviewReassignmentMapper {
    record Pending(
            long applicantId,
            String status,
            int currentVersion,
            int recordVersion,
            Integer workflowVersion) {}

    @Select(
            """
        SELECT p.applicant_id,p.status,p.current_version,p.record_version,a.workflow_version
        FROM policy_apply p LEFT JOIN policy_audit_log a ON a.tenant_key=p.tenant_key AND a.policy_id=p.id
            AND a.version=p.current_version AND a.audit_status='SUBMITTED'
        WHERE p.tenant_key=#{tenant} AND p.id=#{id} AND p.is_deleted=0 AND p.current_version>0
        """)
    Pending find(String tenant, long id);

    @Select(
            """
        SELECT m.user_id FROM policy_review_decision d JOIN policy_review_member m ON m.id=d.member_id
        WHERE d.tenant_key=#{tenant} AND d.policy_id=#{id} AND d.version=#{version}
          AND d.group_code='PRODUCT' AND d.decision='APPROVE'
        """)
    Long productSigner(String tenant, long id, int version);

    @Update(
            """
        <script>UPDATE policy_review_member SET member_status='REMOVED'
        WHERE tenant_key=#{tenant} AND policy_id=#{id} AND version=#{version} AND group_code=#{group}
          AND member_status='ACTIVE' AND id IN
        <foreach collection="memberIds" item="member" open="(" separator="," close=")">#{member}</foreach>
        </script>
        """)
    int remove(String tenant, long id, int version, String group, List<Long> memberIds);

    // 同一申请与轮次已加锁，同人被再次加入时取新序号，历次任务仍对应原指派。
    @Insert(
            """
        <script>INSERT INTO policy_review_member
          (tenant_key,policy_id,version,group_code,user_id,display_name,department,assignment_no)
        SELECT #{tenant},#{id},#{version},#{group},u.id,u.name,u.department,COALESCE(MAX(m.assignment_no),0)+1
        FROM user_info u LEFT JOIN policy_review_member m ON m.tenant_key=u.tenant_key AND m.user_id=u.id
          AND m.policy_id=#{id} AND m.version=#{version} AND m.group_code=#{group}
        WHERE u.tenant_key=#{tenant} AND u.id IN
        <foreach collection="userIds" item="user" open="(" separator="," close=")">#{user}</foreach>
        GROUP BY u.id,u.name,u.department ORDER BY u.id
        </script>
        """)
    int add(String tenant, long id, int version, String group, List<Long> userIds);

    @Update(
            """
        UPDATE policy_apply SET record_version=record_version+1,updated_at=#{at}
        WHERE tenant_key=#{tenant} AND id=#{id} AND current_version=#{version}
          AND record_version=#{recordVersion} AND status='PENDING' AND is_deleted=0
        """)
    int advance(String tenant, long id, int version, int recordVersion, LocalDateTime at);

    @Insert(
            """
        INSERT INTO policy_review_reassignment
          (tenant_key,policy_id,version,group_code,operator_id,reason,before_members,after_members,created_at)
        VALUES (#{tenant},#{id},#{version},#{group},#{actor},#{reason},#{before},#{after},#{at})
        """)
    int audit(
            String tenant,
            long id,
            int version,
            String group,
            long actor,
            String reason,
            String before,
            String after,
            LocalDateTime at);
}
