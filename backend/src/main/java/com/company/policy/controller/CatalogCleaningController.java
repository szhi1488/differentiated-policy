package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.CatalogCleaningRequest.Control;
import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.service.CatalogCleaningService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Log;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Result;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "商品清洗与识别", description = "查看清洗任务、结果、处理日志，复核来源与重试技术失败")
@ApiResponse(responseCode = "400", description = "请求或分页参数无效")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "非有效管理员、租户未授权或写请求来源校验失败")
@ApiResponse(responseCode = "404", description = "当前租户账套下不存在该资源")
@ApiResponse(responseCode = "409", description = "状态、版本或目录目标发生冲突")
@ApiResponse(responseCode = "503", description = "账套未配置或数据库不可用")
public class CatalogCleaningController {
    private final CatalogCleaningService service;
    private final LoginSessionService sessions;

    public CatalogCleaningController(CatalogCleaningService service, LoginSessionService sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/catalog/cleaning/latest")
    @Operation(summary = "获取最近一个成功导入批次的清洗进度")
    @ApiResponse(responseCode = "204", description = "尚无成功的导入批次")
    public ResponseEntity<Job> latest(
            @Parameter(description = "可选，OPPO手机或一加手机") @RequestParam(required = false)
                    String brandName,
            HttpServletRequest request) {
        Job job = service.latest(sessions.requireLogin(request).user(), brandName);
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @GetMapping("/goods-sync/jobs/{id}/cleaning")
    @Operation(summary = "查看指定导入批次的清洗任务及分流计数")
    public Job job(@PathVariable long id, HttpServletRequest request) {
        return service.job(sessions.requireLogin(request).user(), id);
    }

    @PostMapping("/goods-sync/jobs/{id}/clean-start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "为已有成功导入批次启动后台清洗", description = "请求需提供任务 recordVersion，重复启动返回当前进度。")
    @ApiResponse(responseCode = "202", description = "后台清洗已受理或返回现有任务")
    public Job start(
            @PathVariable long id, @Valid @RequestBody Control body, HttpServletRequest request) {
        return service.start(
                sessions.requireLogin(request).user(), id, body.recordVersion(), false);
    }

    @PostMapping("/goods-sync/jobs/{id}/clean-preview")
    @Operation(summary = "只读预览指定批次的规则候选", description = "规则版本默认当前版本，预测结果只依据来源文本。")
    public Page<Result> preview(
            @PathVariable long id,
            @RequestParam(required = false) String ruleVersion,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.preview(sessions.requireLogin(request).user(), id, ruleVersion, page, size);
    }

    @PostMapping("/goods-sync/jobs/{id}/clean-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "重试指定批次的技术失败", description = "请求需提供任务 recordVersion。")
    public Job retry(
            @PathVariable long id, @Valid @RequestBody Control body, HttpServletRequest request) {
        return service.start(sessions.requireLogin(request).user(), id, body.recordVersion(), true);
    }

    @PostMapping("/goods-sync/jobs/{id}/clean-reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "按新规则重新识别旧规则待审记录",
            description = "需要当前任务 recordVersion，仅处理当前批次未映射的旧规则待审商品。")
    public Job reprocess(
            @PathVariable long id, @Valid @RequestBody Control body, HttpServletRequest request) {
        return service.reprocessReviews(
                sessions.requireLogin(request).user(), id, body.recordVersion());
    }

    @GetMapping("/catalog/cleaning/results")
    @Operation(summary = "分页查询清洗结果", description = "页码从 1 开始，每页 1 至 100 条，关键词按商品名或货号查子串。")
    public Page<Result> results(
            @Parameter(description = "可选，导入任务 ID") @RequestParam(required = false) Long importJobId,
            @Parameter(description = "可选，OPPO手机或一加手机") @RequestParam(required = false)
                    String brandName,
            @RequestParam(required = false) Status status,
            @RequestParam(required = false) String ruleVersion,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.results(
                sessions.requireLogin(request).user(),
                importJobId,
                brandName,
                status,
                ruleVersion,
                keyword,
                page,
                size);
    }

    @GetMapping("/source-goods/{id}")
    @Operation(summary = "查看来源七字段、清洗候选与当前规格颜色映射")
    public Detail detail(@PathVariable long id, HttpServletRequest request) {
        return service.detail(sessions.requireLogin(request).user(), id);
    }

    @PostMapping("/source-goods/{id}/review")
    @Operation(
            summary = "通过审核或排除来源",
            description = "APPROVE 必须给出完整 target，EXCLUDE 只填原因。两种操作都需要 recordVersion 和 reason。")
    public Detail review(
            @PathVariable long id, @Valid @RequestBody Review body, HttpServletRequest request) {
        return service.review(sessions.requireLogin(request).user(), id, body);
    }

    @PostMapping("/source-goods/{id}/clean-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "重试单条技术失败", description = "请求需提供来源商品的 recordVersion。")
    public Job retrySource(
            @PathVariable long id, @Valid @RequestBody Control body, HttpServletRequest request) {
        return service.retrySource(sessions.requireLogin(request).user(), id, body.recordVersion());
    }

    @GetMapping("/goods-sync/jobs/{id}/clean-logs")
    @Operation(summary = "分页查看该批次的清洗审计", description = "页码从 1 开始，每页 1 至 100 条。")
    public Page<Log> logs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.logs(sessions.requireLogin(request).user(), id, page, size);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("CATALOG_STORAGE_UNAVAILABLE", "商品清洗暂不可用，请稍后重试。"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("CATALOG_CLEAN_INVALID_REQUEST", "ID、状态或分页参数无效。"));
    }
}
