package com.company.policy.service;

import com.company.policy.dto.UserListQuery;
import com.company.policy.dto.UserPlatformsUpdate;
import com.company.policy.dto.UserRoleUpdate;
import com.company.policy.dto.UserStatusUpdate;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PageResponse;
import com.company.policy.vo.UserDetailResponse;
import com.company.policy.vo.UserPlatformOwner;
import com.company.policy.vo.UserStatsResponse;
import com.company.policy.vo.UserSummaryResponse;
import java.util.List;

/** 供管理员查询和统计本公司账号、调整用户角色及启停账号。 */
public interface UserManagementService {
    List<UserPlatformOwner> platformOwners(CurrentUserResponse session);

    void updatePlatforms(CurrentUserResponse session, long id, UserPlatformsUpdate request);

    /** 按姓名、部门、角色或状态分页查询本公司账号。 */
    PageResponse<UserSummaryResponse> list(CurrentUserResponse session, UserListQuery query);

    /** 统计本公司账号总数、启用申请人数、启用管理员数和停用账号数。 */
    UserStatsResponse stats(CurrentUserResponse session);

    /** 按用户编号查询本公司账号的资料、角色和启停状态。 */
    UserDetailResponse detail(CurrentUserResponse session, long id);

    /** 核对预期角色和启停状态后，调整指定账号的角色。 */
    UserDetailResponse updateRole(CurrentUserResponse session, long id, UserRoleUpdate request);

    /** 核对预期角色和启停状态后，启用或停用指定账号。 */
    UserDetailResponse updateStatus(CurrentUserResponse session, long id, UserStatusUpdate request);
}
