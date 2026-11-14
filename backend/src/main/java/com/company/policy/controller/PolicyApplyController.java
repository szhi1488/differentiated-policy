package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Submission;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/policies")
@Tag(name = "提交政策", description = "仅本公司有效申请人可读写本人申请")
@ApiResponse(responseCode = "400", description = "请求格式、表单枚举、字段组合或完整性有误")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "身份或请求来源不符合要求")
@ApiResponse(responseCode = "404", description = "申请不存在或不属于当前申请人")
@ApiResponse(responseCode = "409", description = "版本、状态、商品在售配置或首次保存标识冲突")
@ApiResponse(responseCode = "503", description = "政策存储不可用")
public class PolicyApplyController {
    private final PolicyApplyService policies;
    private final LoginSessionService sessions;

    public PolicyApplyController(PolicyApplyService policies, LoginSessionService sessions) {
        this.policies = policies;
        this.sessions = sessions;
    }

    @PostMapping(path = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "首次保存草稿",
            description =
                    "渠道 RETAIL 或 BILLION 必填，其他字段可暂缺。省略 recordVersion。可使用 Idempotency-Key UUID 重放相同的首次保存。")
    public PolicyResponse create(
            @RequestBody PolicyDraftRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
            HttpServletRequest request) {
        return policies.create(sessions.requireLogin(request).user(), body, requestKey);
    }

    @PutMapping(path = "/{id}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "继续保存本人草稿或已驳回申请",
            description = "整体保存表单，须提交当前 recordVersion。保存不改变 DRAFT 或 REJECTED 状态。")
    public PolicyResponse save(
            @PathVariable long id,
            @RequestBody PolicyDraftRequest body,
            HttpServletRequest request) {
        return policies.save(sessions.requireLogin(request).user(), id, body);
    }

    @PostMapping(path = "/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "提交已保存的申请",
            description = "仅提交 recordVersion。后端读取已保存表单并复核商品，原单转 PENDING 且新增本轮快照和平台明细。")
    public PolicyResponse submit(
            @PathVariable long id, @RequestBody Submission body, HttpServletRequest request) {
        return policies.submit(sessions.requireLogin(request).user(), id, body.recordVersion());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看本人申请", description = "草稿和驳回读取当前表单及目录信息，待审和通过读取当前轮次提交快照。")
    public Detail detail(@PathVariable long id, HttpServletRequest request) {
        return policies.detail(sessions.requireLogin(request).user(), id);
    }

    @GetMapping("/mine")
    @Operation(summary = "分页查询我的申请", description = "每页 1 至 100 条，按创建时间及 ID 倒序。可按状态、渠道及整单平台组合筛选。")
    public Page<Summary> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @Parameter(description = "DRAFT、PENDING、APPROVED、REJECTED")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "RETAIL、BILLION") @RequestParam(required = false)
                    String perspective,
            @Parameter(description = "整单具体平台编码，例如 JD 或 PDD") @RequestParam(required = false)
                    String platformCode,
            HttpServletRequest request) {
        return policies.mine(
                sessions.requireLogin(request).user(),
                new PolicyListQuery(page, pageSize, status, perspective, platformCode));
    }

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "分页查看本人申请历轮审核", description = "每页 1 至 100 条，按提交轮次倒序。草稿没有历史，待审轮次的审核信息为空。")
    public Page<AuditSummary> auditLogs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return policies.auditLogs(sessions.requireLogin(request).user(), id, page, pageSize);
    }

    @GetMapping("/{id}/audit-logs/{logId}")
    @Operation(summary = "查看本人申请某轮审核及提交快照", description = "logId 取自审核记录列表，返回该轮提交时的表单、商品快照及审核结果。")
    public AuditDetail auditDetail(
            @PathVariable long id, @PathVariable long logId, HttpServletRequest request) {
        return policies.auditDetail(sessions.requireLogin(request).user(), id, logId);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("POLICY_VALIDATION_FAILED", "申请、审核记录 ID 或分页参数格式无效。"));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrentChange() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("POLICY_VERSION_CONFLICT", "申请或商品正在更新，请保留输入并稍后重试。"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("POLICY_STORAGE_UNAVAILABLE", "政策存储暂不可用，请稍后重试。"));
    }
}
