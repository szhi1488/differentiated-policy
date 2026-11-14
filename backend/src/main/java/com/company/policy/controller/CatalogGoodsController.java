package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.dto.CatalogGoodsUpdate;
import com.company.policy.dto.CatalogGoodsWrite;
import com.company.policy.service.CatalogGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.CatalogGoodsResponse;
import com.company.policy.vo.CatalogGoodsResponse.Summary;
import com.company.policy.vo.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/admin/catalog/goods")
@Tag(name = "商品中心", description = "查询、新增、编辑和停用公司共用的标准商品")
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
    @Operation(
            summary = "新增标准商品",
            description = "使用现有品牌、系列和颜色。RAM 用 GB，ROM 支持 GB、T 或 TB。版本可空，销售状态必填。")
    @ApiResponse(responseCode = "409", description = "标准商品重复、系列归属冲突或并发修改")
    public CatalogGoodsResponse create(
            @Valid @RequestBody CatalogGoodsWrite body, HttpServletRequest request) {
        return goods.create(sessions.requireLogin(request).user(), body);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "编辑标准商品",
            description =
                    "提交读取时的 editVersion 作为 expectedVersion，并在 product 中提交完整商品字段。未知销售状态可保留。商品 ID、SKU 和货号保持原值。")
    @ApiResponse(responseCode = "404", description = "商品不存在")
    @ApiResponse(responseCode = "409", description = "商品已变化或已停用、规格重复、系列冲突或引用限制")
    public CatalogGoodsResponse update(
            @PathVariable long id,
            @Valid @RequestBody CatalogGoodsUpdate body,
            HttpServletRequest request) {
        return goods.update(sessions.requireLogin(request).user(), id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "永久删除标准商品", description = "永久删除未被引用的标准商品。商品已不存在时返回 204。")
    @ApiResponse(responseCode = "409", description = "商品关联了来源 SKU，或商品及其配置已被政策引用")
    public void delete(@PathVariable long id, HttpServletRequest request) {
        goods.delete(sessions.requireLogin(request).user(), id);
    }

    @GetMapping
    @Operation(
            summary = "分页查看标准商品",
            description =
                    "品牌精确匹配，系列和机型按子串匹配。关键词最多 128 字符，空格分词后每词均须匹配。"
                            + "支持按商品名、品牌、系列、机型、版本、颜色、货号及标准或来源 SKU 查子串，英文字母不区分大小写。"
                            + "可按系列 ID、机型版本 ID 或 RAM、ROM 精确筛选，两个容量须同时传入，如 16GB 和 512GB。"
                            + "销售状态取 1 在售、2 待上架、3 已下架、4 已停产。页码从 1 开始，每页最多 100 条。")
    public PageResponse<CatalogGoodsResponse> list(
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) String seriesName,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
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

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrentChange() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CATALOG_GOODS_CHANGED", "商品目录正在更新，请稍后重试。"));
    }
}
