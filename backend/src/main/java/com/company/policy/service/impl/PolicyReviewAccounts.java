package com.company.policy.service.impl;

import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 审核分工设置、提交和办理共用的账号资格与加锁顺序。 */
final class PolicyReviewAccounts {
    private PolicyReviewAccounts() {}

    static Map<Long, UserInfo> lockUsers(
            PolicyReviewSettingsMapper mapper, String tenant, Collection<Long> ids) {
        var ordered = List.copyOf(new TreeSet<>(ids));
        var accounts = new HashMap<Long, UserInfo>();
        for (int i = 0; i < ordered.size(); i += 500) {
            for (var user :
                    mapper.lockUsers(tenant, ordered.subList(i, Math.min(i + 500, ordered.size()))))
                accounts.put(user.getId(), user);
        }
        return accounts;
    }

    static boolean eligible(PolicyReviewSettingsMapper settings, UserInfo account, String group) {
        return account != null && settings.eligible(account.getTenantKey(), account.getId(), group);
    }
}
