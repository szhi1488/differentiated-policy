package com.company.policy.service;

import com.company.policy.dto.*;
import com.company.policy.vo.*;
import java.util.List;

/** 管理公司账号、角色分配与平台成员分工。 */
public interface UserManagementService {
    /** 查询全部渠道平台及其成员。 */
    List<PlatformTeam> platformOwners(CurrentUserResponse session);

    /** 按平台版本整体替换分工。 */
    void updatePlatforms(
            CurrentUserResponse session, ChannelPlatform platform, UserPlatformsUpdate request);

    /** 分页查询可担任平台成员或提交人的账号。 */
    PageResponse<PlatformTeam.Candidate> platformCandidates(
            CurrentUserResponse session, String keyword, int page, int pageSize);

    /** 分页查询公司账号及其角色。 */
    PageResponse<UserSummaryResponse> list(CurrentUserResponse session, UserListQuery query);

    /** 分页查询属于对应启用岗位且具有该节点办理权限的有效账号。 */
    PageResponse<PolicyReviewerCandidate> reviewCandidates(
            CurrentUserResponse session,
            String reviewGroup,
            String name,
            String department,
            int page,
            int pageSize);

    /** 统计公司账号总数、启停数及有效管理员数。 */
    UserStatsResponse stats(CurrentUserResponse session);

    /** 查询指定公司账号详情。 */
    UserDetailResponse detail(CurrentUserResponse session, long id);

    /** 按账号版本替换其全部角色。 */
    UserDetailResponse updateRole(CurrentUserResponse session, long id, UserRoleUpdate request);

    /** 按账号版本启用或停用账号。 */
    UserDetailResponse updateStatus(CurrentUserResponse session, long id, UserStatusUpdate request);
}
