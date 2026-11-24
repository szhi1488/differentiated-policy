package com.company.policy.controller;

import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewRoundService;
import com.company.policy.vo.PolicyReviewRoundResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/policy-reviews")
@RequiredArgsConstructor
@Tag(name = "政策审核详情", description = "登录后按当前有效账号、所属企业和所查轮次授权。")
@ApiResponse(responseCode = "401", description = "需要登录")
@ApiResponse(responseCode = "403", description = "账号已停用或不属于本公司")
public class PolicyReviewRoundController {
    private final LoginSessionService sessions;
    private final PolicyReviewRoundService reviews;

    @GetMapping("/{id:[0-9]+}")
    @Operation(
            summary = "读取指定提交轮次",
            description = "返回冻结快照、分流依据、两组成员、实际决定及 allowedActions。须显式传正整数 submissionVersion。")
    @ApiResponse(responseCode = "404", description = "轮次不存在或无阅读权限")
    @ApiResponse(responseCode = "409", description = "提交轮次缺少审核节点")
    public PolicyReviewRoundResponse detail(
            @PathVariable long id,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return reviews.detail(sessions.requireLogin(request).user(), id, submissionVersion);
    }
}
