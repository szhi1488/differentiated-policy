package com.company.policy.mapper;

import com.company.policy.entity.UserInfo;
import com.company.policy.vo.PolicyReviewerCandidate;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PolicyReviewSettingsMapper {
    @Select("SELECT version FROM policy_review_settings WHERE tenant_key=#{tenantKey}")
    Integer version(String tenantKey);

    // 初次保存和首次提交也同步在同一行上；已存在时只取得行锁。
    @Insert(
            """
        INSERT INTO policy_review_settings(tenant_key,version,updated_at) VALUES(#{tenantKey},0,CURRENT_TIMESTAMP)
        ON DUPLICATE KEY UPDATE tenant_key=VALUES(tenant_key)
        """)
    void ensureSettings(String tenantKey);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT version FROM policy_review_settings WHERE tenant_key=#{tenantKey} FOR UPDATE")
    int lockVersion(String tenantKey);

    @Select(
            """
        SELECT m.group_code,u.id,u.name,u.department FROM policy_review_setting_member m
        JOIN user_info u ON u.id=m.user_id AND u.tenant_key=m.tenant_key
        WHERE m.tenant_key=#{tenantKey} ORDER BY m.group_code,m.user_id
        """)
    List<Member> members(String tenantKey);

    record Member(String groupCode, long id, String name, String department) {
        public PolicyReviewerCandidate candidate() {
            return new PolicyReviewerCandidate(Long.toString(id), name, department);
        }
    }

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
        <script>
        SELECT id,tenant_key,open_id,name,department,role_type,is_deleted FROM user_info
        WHERE tenant_key=#{tenantKey} AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE
        </script>
        """)
    List<UserInfo> lockUsers(String tenantKey, List<Long> ids);

    @Update(
            """
        UPDATE policy_review_settings SET version=version+1,updated_by=#{adminId},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_key=#{tenantKey} AND version=#{version}
        """)
    int advance(String tenantKey, long adminId, int version);

    @Delete("DELETE FROM policy_review_setting_member WHERE tenant_key=#{tenantKey}")
    void removeMembers(String tenantKey);

    @Insert(
            """
        <script>
        INSERT INTO policy_review_setting_member(tenant_key,group_code,user_id) VALUES
        <foreach collection="ids" item="id" separator=",">(#{tenantKey},#{group},#{id})</foreach>
        </script>
        """)
    int insertMembers(String tenantKey, String group, List<Long> ids);
}
