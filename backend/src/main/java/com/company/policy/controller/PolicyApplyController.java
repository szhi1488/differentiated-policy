package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.ChannelPlatform;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyDraftRequest.Submission;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.dto.PolicyTimeAdjustmentRequest;
import com.company.policy.dto.PolicyTimelineQuery;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.service.PolicyTimeAdjustments;
import com.company.policy.vo.PolicyApplicability.AdjustmentOption;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Summary;
import com.company.policy.vo.PolicySubsidyTimeline;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@Tag(name = "提交政策", description = "本公司有效申请人读写本人申请，查询负责渠道平台的机型补贴")
@ApiResponse(responseCode = "400", description = "请求格式、表单枚举、字段组合或完整性有误")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "身份、请求来源无效或所选渠道平台未分给本人（POLICY_PLATFORM_FORBIDDEN）")
@ApiResponse(responseCode = "404", description = "申请不存在或不属于当前申请人")
@ApiResponse(responseCode = "409", description = "版本、状态、商品在售配置或首次保存标识冲突")
@ApiResponse(responseCode = "503", description = "政策存储不可用")
public class PolicyApplyController {
    private final PolicyApplyService policies;
    private final LoginSessionService sessions;
    private final PolicyTimeAdjustments timeAdjustments;

    public PolicyApplyController(
            PolicyApplyService policies,
            LoginSessionService sessions,
            PolicyTimeAdjustments timeAdjustments) {
        this.policies = policies;
        this.sessions = sessions;
        this.timeAdjustments = timeAdjustments;
    }

    @GetMapping("/time-adjustments/options")
    @Operation(summary = "查询可独立提前结束或延期的已通过政策")
    public List<AdjustmentOption> timeAdjustmentOptions(HttpServletRequest request) {
        return timeAdjustments.options(sessions.requireLogin(request).user());
    }

    @PostMapping(path = "/time-adjustments/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "核对本批时间调整并预览延期交叉时段", description = "逐单提交原审核轮次、实际截止、新截止和原因，证明文件选传。")
    public PolicyTimeAdjustments.Preview previewTimeAdjustments(
            @RequestBody PolicyTimeAdjustmentRequest body, HttpServletRequest request) {
        return timeAdjustments.preview(
                sessions.requireLogin(request).user(), body.endAdjustments());
    }

    @PostMapping(path = "/time-adjustments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "直接通过并执行一批时间调整",
            description = "UUID 请求标识用于幂等重试。携带预览凭证和各段选择，整批成功后每张目标返回一条已通过的调整记录。")
    public List<PolicyResponse> applyTimeAdjustments(
            @RequestBody PolicyTimeAdjustmentRequest body,
            @RequestHeader("Idempotency-Key") String requestKey,
            HttpServletRequest request) {
        return timeAdjustments.apply(
                sessions.requireLogin(request).user(),
                body.endAdjustments(),
                body.confirmationToken(),
                body.decisions(),
                requestKey);
    }

    @GetMapping("/platforms")
    @Operation(summary = "读取本人可以提交政策的渠道及平台")
    public List<ChannelPlatform> assignedPlatforms(HttpServletRequest request) {
        return policies.assignedPlatforms(sessions.requireLogin(request).user());
    }

    @GetMapping("/subsidy-timeline")
    @Operation(
            summary = "查询本人负责渠道平台的机型补贴",
            description =
                    "日期按北京时间含首尾。RAM、ROM 使用目录原值。省略渠道和平台查询当前全部分工，无分工返回空轨道。可传平台编码，或同时指定渠道 RETAIL／BILLION 和平台。越权返回 403。结果含已通过原单、历史及后续计划。")
    public PolicySubsidyTimeline subsidyTimeline(
            @RequestParam long spuId,
            @RequestParam String ram,
            @RequestParam String rom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String perspective,
            @RequestParam(required = false) String platformCode,
            HttpServletRequest request) {
        return policies.subsidyTimeline(
                sessions.requireLogin(request).user(),
                new PolicyTimelineQuery(
                        spuId, ram, rom, dateFrom, dateTo, perspective, platformCode));
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

    @DeleteMapping("/{id}/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "物理删除本人未提交的草稿",
            description =
                    "recordVersion 必填且为正整数；仅允许 DRAFT、提交轮次为 0 且没有提交记录的本人申请。204 成功，404 不存在或非本人，409 状态或版本冲突。文件保留，由附件接口按引用校验后单独处理。")
    public void deleteDraft(
            @PathVariable long id,
            @RequestParam Integer recordVersion,
            HttpServletRequest request) {
        policies.deleteDraft(sessions.requireLogin(request).user(), id, recordVersion);
    }

    @GetMapping("/{id}/submission-preview")
    @Operation(
            summary = "申请人确认提交范围",
            description =
                    "校验本人草稿或已驳回申请及负责平台。applicability 按公司已通过快照的首尾日期试算分段组合，canDirectSubmit 为预览结论。confirmationToken 对应本次整单判定。")
    public PolicyResponse.SubmissionPreview preview(
            @PathVariable long id,
            @RequestParam Integer recordVersion,
            HttpServletRequest request) {
        return policies.preview(sessions.requireLogin(request).user(), id, recordVersion);
    }

    @PostMapping(path = "/{id}/submission-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "核对随本轮申请送审的政策时间调整",
            description =
                    "可混合缩短和延长。依据候选的 canShorten、canExtend 按方向核权。每项带原截止、新截止和必填原因，证明文件选传。延期与本单一起重算交叉时段。")
    public PolicyResponse.SubmissionPreview previewAdjusted(
            @PathVariable long id,
            @RequestBody PolicyDraftRequest.AdjustmentPreview body,
            HttpServletRequest request) {
        return policies.previewAdjusted(sessions.requireLogin(request).user(), id, body);
    }

    @PostMapping(path = "/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "提交已保存的申请",
            description =
                    "没有交叉且没有整单调整时直提只需 recordVersion；有交叉或整单调整时须带回 confirmationToken、各段 decisions 及相同的 endAdjustments。零售每段提交 SELECT 和 selectedPolicyIds，完整场景组合与核销类型相同的政策最多选一张。选择缺失、无效或范围变化返回 409。成功转 PENDING 并保存本轮快照和明细。启用工作流的新轮返回 reviewStage（PRODUCT_REVIEW 或 PURCHASE_REVIEW）；所需名单缺失时返回 409。")
    public PolicyResponse submit(
            @PathVariable long id, @RequestBody Submission body, HttpServletRequest request) {
        return policies.submit(sessions.requireLogin(request).user(), id, body);
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
