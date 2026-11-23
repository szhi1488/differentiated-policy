package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.PolicyReviewQuery;
import com.company.policy.dto.PolicyReviewRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyReviewResponse.Detail;
import com.company.policy.vo.PolicyReviewResponse.Effective;
import com.company.policy.vo.PolicyReviewResponse.OverridePreview;
import com.company.policy.vo.PolicyReviewResponse.Page;
import com.company.policy.vo.PolicyReviewResponse.Review;
import com.company.policy.vo.PolicySubsidyTimeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/admin/policies")
@Tag(name = "政策审核与生效", description = "本公司有效管理员可审核已提交政策及查询生效明细")
@ApiResponse(responseCode = "400", description = "筛选、审核参数或时间格式有误")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "当前身份不可执行此操作")
@ApiResponse(responseCode = "404", description = "申请不存在或不在管理员可见范围内")
@ApiResponse(responseCode = "409", description = "待审状态或提交版本已更新")
@ApiResponse(responseCode = "503", description = "政策存储不可用")
public class PolicyReviewController {
    private final LoginSessionService sessions;
    private final PolicyReviewService policies;

    public PolicyReviewController(LoginSessionService sessions, PolicyReviewService policies) {
        this.sessions = sessions;
        this.policies = policies;
    }

    @GetMapping
    @Operation(
            summary = "分页查看已提交的政策",
            description =
                    "业务字段展示和筛选以最近正式提交快照为准，驳回后暂存修改在重提成功后更新。按本轮提交时间倒序。状态可选 PENDING、APPROVED 或 REJECTED。提交日期按北京时间筛选。")
    public Page list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String perspective,
            @RequestParam(required = false) String platformCode,
            @RequestParam(required = false) String verifyType,
            @RequestParam(required = false) String sceneType,
            @RequestParam(required = false) String applicant,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate submittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate submittedTo,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        return policies.list(
                sessions.requireLogin(request).user(),
                new PolicyReviewQuery(
                        page,
                        pageSize,
                        status,
                        perspective,
                        platformCode,
                        verifyType,
                        sceneType,
                        applicant,
                        submittedFrom == null ? null : submittedFrom.atStartOfDay(),
                        submittedTo == null ? null : submittedTo.plusDays(1).atStartOfDay(),
                        keyword));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "读取最近提交快照及覆盖范围",
            description = "管理页面传 includeCoverage=false，仅返回审核内容。省略时兼容返回覆盖范围。")
    public Detail detail(
            @PathVariable long id,
            @RequestParam(defaultValue = "true") boolean includeCoverage,
            HttpServletRequest request) {
        return policies.detail(sessions.requireLogin(request).user(), id, includeCoverage);
    }

    @PostMapping("/{id}/review")
    @Operation(
            summary = "通过或驳回当前提交轮次",
            description = "须提供实际记录版本、提交轮次和 APPROVE 或 REJECT。审核时间由服务端记录。")
    public Review review(
            @PathVariable long id,
            @RequestBody PolicyReviewRequest body,
            HttpServletRequest request) {
        return policies.review(sessions.requireLogin(request).user(), id, body);
    }

    @GetMapping("/{id}/override-preview")
    @Operation(summary = "查看当前待审轮次覆盖预览", description = "按本轮实际提交顺序和假设通过时间复核，返回分段、冻结决定及下一步操作。")
    public OverridePreview preview(
            @PathVariable long id,
            @RequestParam int recordVersion,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return policies.preview(
                sessions.requireLogin(request).user(), id, recordVersion, submissionVersion);
    }

    @GetMapping("/subsidy-timeline")
    @ApiResponse(responseCode = "409", description = "历史涉及旧政策且未配置规则切换时间，POLICY_CUTOVER_REQUIRED")
    @Operation(
            summary = "查询机型配置的零售及百亿补贴时间轴",
            description =
                    "日期按北京时间含首尾。RAM、ROM 使用目录原值，如 16GB、1024GB。省略平台时返回全部平台。segments 为截至 asOf 的实际名单，plannedSegments 为按已通过决定计算的后续各段采用和未采用名单。")
    public PolicySubsidyTimeline subsidyTimeline(
            @RequestParam long spuId,
            @RequestParam String ram,
            @RequestParam String rom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String platformCode,
            HttpServletRequest request) {
        return policies.subsidyTimeline(
                sessions.requireLogin(request).user(),
                new PolicyTimelineQuery(spuId, ram, rom, dateFrom, dateTo, null, platformCode));
    }

    @GetMapping("/effective")
    @Operation(
            summary = "查询渠道、平台及商品在指定时点适用的政策",
            description = "按北京时间及本轮确认决定返回所有实际适用政策，单一结果兼容 winner 和 snapshot。")
    public Effective effective(
            @RequestParam String perspective,
            @RequestParam String platformCode,
            @RequestParam long goodsId,
            @Parameter(description = "北京时间，例如 2026-11-15T09:00:00+08:00，默认当前时间")
                    @RequestParam(required = false)
                    String at,
            HttpServletRequest request) {
        return policies.effective(
                sessions.requireLogin(request).user(), perspective, platformCode, goodsId, at);
    }

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "分页查看申请的历轮审核")
    public com.company.policy.vo.PolicyResponse.Page<AuditSummary> auditLogs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return policies.auditLogs(sessions.requireLogin(request).user(), id, page, pageSize);
    }

    @GetMapping("/{id}/audit-logs/{logId}")
    @Operation(summary = "查看指定轮次审核和历史提交快照")
    public AuditDetail auditDetail(
            @PathVariable long id, @PathVariable long logId, HttpServletRequest request) {
        return policies.auditDetail(sessions.requireLogin(request).user(), id, logId);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("POLICY_STORAGE_UNAVAILABLE", "政策存储暂不可用，请稍后重试。"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> badParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("POLICY_VALIDATION_FAILED", "筛选、申请标识或日期格式无效。"));
    }
}
