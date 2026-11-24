package com.company.policy.controller;

import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyDeliveryRecovery;
import com.company.policy.service.PolicyDeliveryRecovery.Item;
import com.company.policy.service.PolicyDeliveryRecovery.Resolution;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/policy-review-deliveries")
public class PolicyDeliveryController {
    private final LoginSessionService sessions;
    private final PolicyDeliveryRecovery recovery;

    @GetMapping
    @Operation(summary = "按本公司政策和轮次逐项核对飞书投递")
    public List<Item> list(
            @RequestParam long policyId,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return recovery.list(sessions.requireLogin(request).user(), policyId, submissionVersion);
    }

    @GetMapping("/notices")
    @Operation(summary = "按游标查询公司业务提醒，每页最多五十条")
    public List<Item> notices(
            @RequestParam(defaultValue = "9223372036854775807") long before,
            HttpServletRequest request) {
        return recovery.notices(sessions.requireLogin(request).user(), before);
    }

    @PostMapping("/{id}/resolve")
    @Operation(
            summary = "登记核对依据后处理单项投递",
            description = "CONFIRM_RECEIPT 核对外部回执；RETRY_CONFIRMED 将已排除重复风险的动作重新入队。")
    @ApiResponse(responseCode = "409", description = "投递状态已变化或无法核对外部回执")
    public Item resolve(
            @PathVariable long id, @RequestBody Resolution body, HttpServletRequest request) {
        return recovery.resolve(sessions.requireLogin(request).user(), id, body);
    }
}
