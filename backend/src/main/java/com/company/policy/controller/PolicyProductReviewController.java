package com.company.policy.controller;

import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.vo.PolicyReviewResponse.Review;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "政策经办")
public class PolicyProductReviewController {
    private final LoginSessionService sessions;
    private final PolicyReviewService reviews;

    public PolicyProductReviewController(
            LoginSessionService sessions, PolicyReviewService reviews) {
        this.sessions = sessions;
        this.reviews = reviews;
    }

    @PostMapping("/api/policy-reviews/{id}/product-review")
    @Operation(
            summary = "办理本轮产品运营初审",
            description = "当前有效初审成员携带记录版本、提交轮次，选择 APPROVE 转采购或 REJECT 退回。退回原因必填，最多 512 字。")
    @ApiResponse(responseCode = "400", description = "版本、决定或退回原因格式无效")
    @ApiResponse(responseCode = "401", description = "会话无效")
    @ApiResponse(responseCode = "403", description = "当前账号或指派无办理权")
    @ApiResponse(responseCode = "404", description = "本轮申请不可见")
    @ApiResponse(responseCode = "409", description = "状态或版本已更新，或需管理员改派采购")
    public Review review(
            @PathVariable long id,
            @RequestBody PolicyReviewRequest body,
            HttpServletRequest request) {
        return reviews.productReview(sessions.requireLogin(request).user(), id, body);
    }
}
