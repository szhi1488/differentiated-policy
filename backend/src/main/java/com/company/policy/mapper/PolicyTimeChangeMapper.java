package com.company.policy.mapper;

import com.company.policy.entity.PolicyApply;
import com.company.policy.entity.PolicyTimeChange;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PolicyTimeChangeMapper {
    String VISIBLE =
            """
            SELECT c.* FROM policy_time_change c
            JOIN policy_apply p ON p.id=c.policy_id AND p.tenant_key=c.tenant_key AND p.is_deleted=0
            WHERE c.tenant_key=#{tenant}
            """;

    // 原单详情只读本轮展示字段，分段采用决定可能较大，留给执行查询按需读取。
    @Select(
            """
            SELECT c.id,c.direction,c.previous_end,c.requested_end,c.actual_end,c.effective_at,
                   c.changed_at,c.actor_name,c.reason,c.source_policy_id,c.attachment_json
            FROM policy_time_change c JOIN policy_apply p
              ON p.id=c.policy_id AND p.tenant_key=c.tenant_key AND p.is_deleted=0
            WHERE c.tenant_key=#{tenant} AND c.policy_id=#{policyId} AND c.policy_version=#{version}
            ORDER BY c.after_record_version DESC
            """)
    List<PolicyTimeChange> history(String tenant, long policyId, int version);

    @Select(VISIBLE + " AND c.policy_id=#{policyId} AND c.id=#{id}")
    PolicyTimeChange byId(String tenant, long policyId, long id);

    @Select(VISIBLE + " AND c.actor_id=#{actorId} AND c.request_key=#{requestKey} ORDER BY c.id")
    List<PolicyTimeChange> byRequest(String tenant, long actorId, String requestKey);

    @Select(
            "<script>"
                    + VISIBLE
                    + """
            AND c.changed_at &lt;= #{asOf} AND c.policy_id IN
            <foreach collection="policyIds" item="id" open="(" close=")" separator=",">#{id}</foreach>
            ORDER BY c.changed_at,c.id</script>
            """)
    List<PolicyTimeChange> through(String tenant, LocalDateTime asOf, List<Long> policyIds);

    @Select(
            "SELECT "
                    + PolicyApplyMapper.COLUMNS
                    + """
            FROM policy_apply WHERE id=#{policyId} AND tenant_key=#{tenant}
            AND is_deleted=0 AND status='APPROVED' FOR UPDATE
            """)
    PolicyApply original(String tenant, long policyId);

    @Update(
            """
            UPDATE policy_apply SET end_time=#{actualEnd},record_version=record_version+1,updated_at=#{changedAt}
            WHERE id=#{policyId} AND tenant_key=#{tenant} AND is_deleted=0 AND status='APPROVED'
              AND record_version=#{recordVersion} AND end_time=#{previousEnd}
            """)
    int updateEnd(
            String tenant,
            long policyId,
            int recordVersion,
            LocalDateTime previousEnd,
            LocalDateTime actualEnd,
            LocalDateTime changedAt);

    @Insert(
            """
            INSERT INTO policy_time_change
              (policy_id,tenant_key,policy_version,audit_log_id,after_record_version,direction,
               previous_end,requested_end,actual_end,effective_at,changed_at,reason,actor_id,actor_name,
               attachment_json,consent_json,request_key,request_hash,source_policy_id,source_version)
            VALUES (#{policyId},#{tenantKey},#{policyVersion},#{auditLogId},#{afterRecordVersion},#{direction},
                    #{previousEnd},#{requestedEnd},#{actualEnd},#{effectiveAt},#{changedAt},#{reason},#{actorId},
                    #{actorName},#{attachmentJson},#{consentJson},#{requestKey},#{requestHash},#{sourcePolicyId},#{sourceVersion})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PolicyTimeChange row);
}
