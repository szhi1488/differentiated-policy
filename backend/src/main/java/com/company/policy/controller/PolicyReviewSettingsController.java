package com.company.policy.controller;

import com.company.policy.dto.PolicyReviewSettingsUpdate;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewSettingsService;
import com.company.policy.service.UserManagementService;
import com.company.policy.vo.PageResponse;
import com.company.policy.vo.PolicyReviewSettings;
import com.company.policy.vo.PolicyReviewerCandidate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/policy-review-settings")
@Tag(name = "政策审核设置")
public class PolicyReviewSettingsController {
    private final PolicyReviewSettingsService settings;
    private final UserManagementService users;
    private final LoginSessionService sessions;

    public PolicyReviewSettingsController(
            PolicyReviewSettingsService settings,
            UserManagementService users,
            LoginSessionService sessions) {
        this.settings = settings;
        this.users = users;
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "读取本企业审核人员设置及当前配置版本")
    public PolicyReviewSettings read(HttpServletRequest request) {
        return settings.read(sessions.requireLogin(request).user());
    }

    @PutMapping
    @Operation(summary = "按期望版本保存本企业两组审核人员", description = "同一组内自动去重，保存后的名单供后续新提交轮次使用。")
    @ApiResponse(responseCode = "409", description = "POLICY_REVIEW_SETTINGS_CONFLICT：配置版本已变化")
    public PolicyReviewSettings save(
            @Valid @RequestBody PolicyReviewSettingsUpdate body, HttpServletRequest request) {
        return settings.save(sessions.requireLogin(request).user(), body);
    }

    @GetMapping("/candidates")
    @Operation(
            summary = "按审核岗位搜索本公司启用的候选人员",
            description = "姓名与部门可组合筛选，同名账号按本地 ID 区分。采购岗位只返回当前有效管理员。")
    @ApiResponse(responseCode = "400", description = "岗位、筛选条件或分页参数无效")
    @ApiResponse(responseCode = "401", description = "LOGIN_REQUIRED：无有效登录会话")
    @ApiResponse(
            responseCode = "403",
            description = "USER_ADMIN_REQUIRED 或 USER_SCOPE_DENIED：无设置权限")
    public PageResponse<PolicyReviewerCandidate> candidates(
            @Parameter(description = "审核岗位，PRODUCT 为产品运营，PURCHASE 为采购") @RequestParam
                    String reviewGroup,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        return users.reviewCandidates(
                sessions.requireLogin(request).user(),
                reviewGroup,
                name,
                department,
                page,
                pageSize);
    }
}
