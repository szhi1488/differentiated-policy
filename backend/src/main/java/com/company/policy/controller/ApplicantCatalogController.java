package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.dto.CatalogGoodsQuery.Configuration;
import com.company.policy.service.CatalogGoodsService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.ApplicantCatalogResponse.Options;
import com.company.policy.vo.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/catalog")
@Tag(name = "申请人选品", description = "查询本公司在售标准商品")
@ApiResponse(responseCode = "400", description = "筛选条件或分页无效")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "非本公司有效申请人")
@ApiResponse(responseCode = "503", description = "目录存储不可用")
public class ApplicantCatalogController {
    private final CatalogGoodsService goods;
    private final LoginSessionService sessions;

    public ApplicantCatalogController(CatalogGoodsService goods, LoginSessionService sessions) {
        this.goods = goods;
        this.sessions = sessions;
    }

    @GetMapping("/options")
    @Operation(summary = "读取在售品牌、系列、机型版本和配置", description = "下级选项受传入的上级 ID 限制。")
    public Options options(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long spuId,
            HttpServletRequest request) {
        return goods.onSaleOptions(sessions.requireLogin(request).user(), brandId, seriesId, spuId);
    }

    @GetMapping("/goods")
    @Operation(
            summary = "分页查看在售商品",
            description =
                    "按品牌、系列、机型版本和关键词筛选。可成对传 RAM 和 ROM，或重复传 configuration=机型ID/RAM/ROM 以匹配任一配置。页码从 1 开始，每页最多 100 件，按商品 ID 升序。")
    public PageResponse<ApplicantCatalogResponse> goods(
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long spuId,
            @RequestParam(required = false) String ram,
            @RequestParam(required = false) String rom,
            @RequestParam(name = "configuration", required = false) String[] configurations,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        return goods.onSale(
                sessions.requireLogin(request).user(),
                new CatalogGoodsQuery(
                        null,
                        null,
                        null,
                        keyword,
                        1,
                        true,
                        page,
                        size,
                        seriesId,
                        spuId,
                        ram,
                        rom,
                        brandId,
                        configurations(configurations)));
    }

    private static List<Configuration> configurations(String[] values) {
        if (values == null) {
            return List.of();
        }
        try {
            return Arrays.stream(values)
                    .map(
                            value -> {
                                String[] parts = value.split("/", -1);
                                if (parts.length != 3 || !parts[0].matches("[1-9][0-9]{0,18}")) {
                                    throw new IllegalArgumentException();
                                }
                                return new Configuration(
                                        Long.parseLong(parts[0]), parts[1], parts[2]);
                            })
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CATALOG_INVALID_FILTER", "商品配置筛选参数无效。");
        }
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("CATALOG_INVALID_FILTER", "商品查询参数无效。"));
    }
}
