package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.CatalogGoodsCreate;
import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.dto.CatalogGoodsQuery.CatalogStatus;
import com.company.policy.service.CatalogGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogGoodsResponse;
import com.company.policy.vo.CatalogGoodsResponse.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/admin/catalog/goods")
@Tag(name = "商品中心", description = "查询、新增和停用公司共用的标准商品")
@ApiResponse(responseCode = "400", description = "商品字段、筛选条件或分页无效")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "非公司有效管理员")
@ApiResponse(responseCode = "503", description = "公司租户未配置或数据库不可用")
public class CatalogGoodsController {
    private final CatalogGoodsService goods;
    private final LoginSessionService sessions;

    public CatalogGoodsController(CatalogGoodsService goods, LoginSessionService sessions) {
        this.goods = goods;
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新增标准商品", description = "使用现有品牌、系列和颜色。RAM 用 GB，ROM 支持 GB、T 或 TB。版本可空。")
    @ApiResponse(responseCode = "409", description = "标准商品重复、系列归属冲突或并发修改")
    public CatalogGoodsResponse create(
            @Valid @RequestBody CatalogGoodsCreate body, HttpServletRequest request) {
        return goods.create(sessions.requireLogin(request).user(), body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除商品（停用）", description = "从默认列表移除商品并保留引用，重复请求仍返回 204。")
    @ApiResponse(responseCode = "404", description = "商品不存在")
    public void disable(@PathVariable long id, HttpServletRequest request) {
        goods.disable(sessions.requireLogin(request).user(), id);
    }

    @GetMapping
    @Operation(
            summary = "分页查看标准商品",
            description =
                    "品牌精确匹配，系列和机型按子串匹配。关键词查询商品名、货号、颜色及标准或来源 SKU。"
                            + "可按系列 ID、机型版本 ID 或 RAM、ROM 精确筛选，两个容量须同时传入，如 16GB 和 512GB。"
                            + "目录状态省略时只查可选和草稿。销售状态取 1 在售、2 待上架、3 已下架、4 已停产。页码从 1 开始，每页最多 100 条。")
    public Page<CatalogGoodsResponse> list(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String seriesName,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) CatalogStatus catalogStatus,
            @RequestParam(defaultValue = "false") boolean ascending,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long spuId,
            @RequestParam(required = false) String ram,
            @RequestParam(required = false) String rom,
            HttpServletRequest request) {
        return goods.list(
                sessions.requireLogin(request).user(),
                new CatalogGoodsQuery(
                        brandName,
                        seriesName,
                        modelName,
                        keyword,
                        status,
                        catalogStatus,
                        ascending,
                        page,
                        size,
                        seriesId,
                        spuId,
                        ram,
                        rom));
    }

    @GetMapping("/summary")
    @Operation(summary = "查看商品统计、品牌系列及机型版本计数和配置选项")
    public Summary summary(HttpServletRequest request) {
        return goods.summary(sessions.requireLogin(request).user());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看标准商品详情")
    @ApiResponse(responseCode = "404", description = "商品不存在")
    public CatalogGoodsResponse detail(@PathVariable long id, HttpServletRequest request) {
        return goods.detail(sessions.requireLogin(request).user(), id);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("CATALOG_INVALID_FILTER", "商品 ID、状态或分页参数无效。"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("CATALOG_STORAGE_UNAVAILABLE", "标准商品暂不可用，请稍后重试。"));
    }

    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ApiError> concurrentChange() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CATALOG_GOODS_CHANGED", "商品目录正在更新，请稍后重试。"));
    }
}
