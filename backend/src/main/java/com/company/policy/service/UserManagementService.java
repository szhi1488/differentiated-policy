package com.company.policy.service;

import com.company.policy.dto.UserListQuery;
import com.company.policy.dto.UserRoleUpdate;
import com.company.policy.dto.UserStatusUpdate;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PageResponse;
import com.company.policy.vo.UserDetailResponse;
import com.company.policy.vo.UserStatsResponse;
import com.company.policy.vo.UserSummaryResponse;

public interface UserManagementService {
    PageResponse<UserSummaryResponse> list(CurrentUserResponse session, UserListQuery query);

    UserStatsResponse stats(CurrentUserResponse session);

    UserDetailResponse detail(CurrentUserResponse session, long id);

    UserDetailResponse updateRole(CurrentUserResponse session, long id, UserRoleUpdate request);

    UserDetailResponse updateStatus(CurrentUserResponse session, long id, UserStatusUpdate request);
}
