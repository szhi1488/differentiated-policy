package com.company.policy.service.impl;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.UserInfo;
import com.company.policy.mapper.PolicyReviewSettingsMapper;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;

/** 默认名单与在途指派共用的账号资格和加锁顺序。 */
final class PolicyReviewAccounts {
    private PolicyReviewAccounts() {}

    static List<Long> distinctIds(List<String> values, String errorCode) {
        if (values == null) throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "请提供审核人员列表。");
        var ids = new TreeSet<Long>();
        for (var value : values) {
            if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "审核人 ID 须为正整数字符串。");
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException error) {
                throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "审核人 ID 超出范围。");
            }
        }
        return List.copyOf(ids);
    }

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

    static boolean eligible(UserInfo account, String group) {
        return account != null
                && Integer.valueOf(0).equals(account.getIsDeleted())
                && ("PRODUCT".equals(group) || "ADMIN".equals(account.getRoleType()));
    }
}
