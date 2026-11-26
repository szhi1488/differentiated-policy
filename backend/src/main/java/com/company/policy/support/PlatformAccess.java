package com.company.policy.support;

import com.company.policy.dto.ChannelPlatform;
import com.company.policy.mapper.RoleAccessMapper;
import com.company.policy.mapper.UserPlatformMapper;
import com.company.policy.service.ExecutionNotices;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 撤回岗位或停用账号时，在同一事务收回失去资格的平台席位。 */
@Component
@RequiredArgsConstructor
public class PlatformAccess {
    private final UserPlatformMapper teams;
    private final RoleAccessMapper roles;
    private final ExecutionNotices notices;

    /** 具备正式政策或机型补贴的平台查看权，都可作为共同成员。 */
    public boolean canRead(String tenant, long id) {
        return roles.hasPermission(tenant, id, "policy.read.platform_submitted") > 0
                || roles.hasPermission(tenant, id, "subsidy.read.platform") > 0;
    }

    public void reconcile(String tenant, long id, long actor) {
        boolean submit = roles.hasPermission(tenant, id, "policy.apply") > 0;
        if (submit) return;
        boolean read = canRead(tenant, id);
        for (ChannelPlatform scope : teams.memberships(tenant, id)) {
            String kind = teams.kind(tenant, scope, id);
            if (read && !"SUBMITTER".equals(kind)) continue;
            teams.lockTeam(tenant, scope);
            if (read) teams.demote(tenant, scope, id);
            else teams.remove(tenant, scope, id);
            if ("SUBMITTER".equals(kind)) notices.platformChanged(tenant, scope, id, null);
            teams.advance(tenant, scope, actor);
        }
    }
}
