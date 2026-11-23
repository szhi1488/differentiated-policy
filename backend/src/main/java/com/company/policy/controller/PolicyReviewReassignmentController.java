package com.company.policy.controller;

import com.company.policy.dto.PolicyReviewReassignmentRequest;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewReassignmentService;
import com.company.policy.vo.PolicyReviewReassignmentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/policy-reviews")
@Tag(name = "政策审核改派")
public class PolicyReviewReassignmentController {
    private final PolicyReviewReassignmentService reassignments;
    private final LoginSessionService sessions;

    public PolicyReviewReassignmentController(
            PolicyReviewReassignmentService reassignments, LoginSessionService sessions) {
        this.reassignments = reassignments;
        this.sessions = sessions;
    }

    @PostMapping("/{id}/reassign")
    @Operation(summary = "按本轮目标节点的新名单改派", description = "限本企业有效管理员。初审期间可改派当前初审或后续采购，进入终审后仅可改派采购。")
    @ApiResponse(responseCode = "400", description = "名单、目标资格或改派原因不合法")
    @ApiResponse(responseCode = "403", description = "当前账号无管理员权限")
    @ApiResponse(responseCode = "404", description = "申请不可见")
    @ApiResponse(responseCode = "409", description = "申请、轮次或审核阶段已变化")
    public PolicyReviewReassignmentResult reassign(
            @PathVariable long id,
            @Valid @RequestBody PolicyReviewReassignmentRequest body,
            HttpServletRequest request) {
        return reassignments.reassign(sessions.requireLogin(request).user(), id, body);
    }
}
