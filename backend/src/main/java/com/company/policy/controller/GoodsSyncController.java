package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.service.GoodsSyncService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.GoodsSyncResponse;
import com.company.policy.vo.GoodsSyncResponse.Overview;
import com.company.policy.vo.GoodsSyncResponse.SourcePage;
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
@Tag(name = "源商品库", description = "管理员按品牌导入和查询吉客云商品原文")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "非有效管理员、未授权租户或写请求来源校验失败")
@ApiResponse(responseCode = "503", description = "账套未配置或数据库不可用")
public class GoodsSyncController {
    private final GoodsSyncService service;
    private final LoginSessionService sessions;

    public GoodsSyncController(GoodsSyncService service, LoginSessionService sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @GetMapping("/goods-sync/jobs")
    @Operation(summary = "查看两个品牌各自的最新任务")
    public Overview overview(HttpServletRequest request) {
        return service.overview(sessions.requireLogin(request).user());
    }

    @PostMapping("/goods-sync/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "发起一个品牌的只读导入", description = "仅查询启用商品；同一账套同时只处理一个任务。")
    @ApiResponse(responseCode = "202", description = "已受理，结果以任务状态为准")
    @ApiResponse(responseCode = "400", description = "品牌或幂等键无效")
    @ApiResponse(responseCode = "409", description = "有活动任务、处于冷却期或幂等键冲突")
    public GoodsSyncResponse start(
            @Valid @RequestBody GoodsSyncRequest body, HttpServletRequest request) {
        return service.start(sessions.requireLogin(request).user(), body);
    }

    @PostMapping("/goods-sync/jobs/{id}/resume")
    @Operation(summary = "从已提交的断点继续暂停的任务")
    @ApiResponse(responseCode = "404", description = "当前租户账套不存在该任务")
    @ApiResponse(responseCode = "409", description = "状态或版本冲突、仍在冷却或达到页数上限")
    public GoodsSyncResponse resume(
            @PathVariable long id,
            @Valid @RequestBody GoodsSyncRequest.Control body,
            HttpServletRequest request) {
        return service.resume(sessions.requireLogin(request).user(), id, body.recordVersion());
    }

    @PostMapping("/goods-sync/jobs/{id}/cancel")
    @Operation(summary = "结束暂停任务，保留已提交商品原文")
    @ApiResponse(responseCode = "404", description = "当前租户账套不存在该任务")
    @ApiResponse(responseCode = "409", description = "任务未暂停或版本已变化")
    public GoodsSyncResponse cancel(
            @PathVariable long id,
            @Valid @RequestBody GoodsSyncRequest.Control body,
            HttpServletRequest request) {
        return service.cancel(sessions.requireLogin(request).user(), id, body.recordVersion());
    }

    @GetMapping("/source-goods")
    @Operation(summary = "分页读取已经落库的七字段原文")
    @ApiResponse(responseCode = "400", description = "品牌、关键词或分页参数无效")
    public SourcePage sources(
            @Parameter(description = "不传为全部；可选 OPPO手机、一加手机") @RequestParam(required = false)
                    String brandName,
            @Parameter(description = "商品名称、货号或 SKU 名称的子串，最多 128 字符") @RequestParam(required = false)
                    String keyword,
            @Parameter(description = "从 1 开始") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页 1 至 100 条") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return service.sources(
                sessions.requireLogin(request).user(), brandName, keyword, page, size);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("CATALOG_STORAGE_UNAVAILABLE", "源商品库暂不可用，请检查数据库连接和九表建表版本。"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "任务 ID 或分页参数无效。"));
    }
}
