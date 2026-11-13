package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.CatalogCleaningRequest.Retry;
import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.dto.CatalogCleaningRequest.Start;
import com.company.policy.service.CatalogCleaningService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Log;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Preview;
import com.company.policy.vo.CatalogCleaningResponse.Results;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    @GetMapping("/catalog/cleaning/jobs")
    @Operation(summary = "分页查看清洗记录", description = "页码从 1 开始，每页 1 至 100 条。")
    public Page<Job> jobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        return service.jobs(sessions.requireLogin(request).user(), page, size);
    }

    @GetMapping("/catalog/cleaning/jobs/active")
    @Operation(summary = "查看账套当前正在运行的清洗任务")
    @ApiResponse(responseCode = "204", description = "当前没有正在运行的任务")
    public ResponseEntity<Job> active(HttpServletRequest request) {
        Job job = service.active(sessions.requireLogin(request).user());
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @GetMapping("/catalog/cleaning/jobs/{id}")
    @Operation(summary = "查看指定清洗任务的进度和结果统计")
    public Job job(@PathVariable long id, HttpServletRequest request) {
        return service.job(sessions.requireLogin(request).user(), id);
    }

    @PostMapping("/catalog/cleaning/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "按品牌和可选建档日启动本地清洗",
            description = "品牌必填，日期为空表示该品牌全部。需提交确认数量和幂等请求键，数量变化返回 409。")
    public Job start(@Valid @RequestBody Start body, HttpServletRequest request) {
        return service.start(sessions.requireLogin(request).user(), body);
    }

    @GetMapping("/catalog/cleaning/preview")
    @Operation(
            summary = "预览本地来源商品及预计清洗数量",
            description = "品牌必填。关键词仅筛选预览表，预计清洗数量按品牌、建档日及是否包含未映射待审计算。")
    public Preview preview(
            @RequestParam String brandName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate createdDate,
            @RequestParam(defaultValue = "false") boolean includeReviews,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        return service.preview(
                sessions.requireLogin(request).user(),
                brandName,
                createdDate,
                includeReviews,
                keyword,
                page,
                size);
    }

    @GetMapping("/catalog/cleaning/results")
    @Operation(
            summary = "分页查询清洗结果及状态计数",
            description =
                    "关键词匹配商品名或货号。日期与 unknownDate 互斥。状态可选 PENDING、PASSED、REVIEW_REQUIRED、ERROR、EXCLUDED。")
    public Results results(
            @Parameter(description = "省略时包含 OPPO 手机和一加手机") @RequestParam(required = false)
                    String brandName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate createdDate,
            @RequestParam(defaultValue = "false") boolean unknownDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.results(
                sessions.requireLogin(request).user(),
                brandName,
                createdDate,
                unknownDate,
                status,
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
            description = "两种操作均需 recordVersion。APPROVE 需要完整 target，EXCLUDE 无需 target。reason 可省略。")
    public Detail review(
            @PathVariable long id, @Valid @RequestBody Review body, HttpServletRequest request) {
        return service.review(sessions.requireLogin(request).user(), id, body);
    }

    @PostMapping("/source-goods/{id}/clean-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "重试单条技术失败", description = "请求需提供来源商品的 recordVersion。")
    public Job retrySource(
            @PathVariable long id, @Valid @RequestBody Retry body, HttpServletRequest request) {
        return service.retrySource(
                sessions.requireLogin(request).user(), id, body.recordVersion(), body.requestKey());
    }

    @GetMapping("/catalog/cleaning/jobs/{id}/logs")
    @Operation(summary = "分页查看该批次的清洗审计", description = "页码从 1 开始，每页 1 至 100 条。")
    public Page<Log> logs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.logs(sessions.requireLogin(request).user(), id, page, size);
    }

    @GetMapping("/source-goods/{id}/clean-logs")
    @Operation(summary = "分页查看指定来源商品的处理历史")
    public Page<Log> sourceLogs(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        return service.sourceLogs(sessions.requireLogin(request).user(), id, page, size);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("CATALOG_STORAGE_UNAVAILABLE", "商品清洗暂不可用，请稍后重试。"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("CATALOG_CLEAN_INVALID_REQUEST", "ID、日期、状态或分页参数无效。"));
    }
}
