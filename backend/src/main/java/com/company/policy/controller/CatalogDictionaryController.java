package com.company.policy.controller;

import com.company.policy.common.api.ApiError;
import com.company.policy.dto.CatalogDictionaryRequest.Create;
import com.company.policy.dto.CatalogDictionaryRequest.Rename;
import com.company.policy.dto.CatalogDictionaryRequest.Type;
import com.company.policy.service.CatalogDictionaryService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.CatalogDictionaryEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/admin/catalog/dictionary")
@Tag(name = "商品数据字典", description = "维护公司共用的品牌、系列和颜色")
@ApiResponse(responseCode = "400", description = "参数无效")
@ApiResponse(responseCode = "401", description = "会话无效")
@ApiResponse(responseCode = "403", description = "非公司有效管理员或请求来源校验失败")
@ApiResponse(responseCode = "404", description = "字典项不存在")
@ApiResponse(responseCode = "409", description = "名称重复、记录已变化、存在引用或并发冲突")
@ApiResponse(responseCode = "503", description = "公司租户未配置或数据库不可用")
public class CatalogDictionaryController {
    private final CatalogDictionaryService dictionary;
    private final LoginSessionService sessions;

    public CatalogDictionaryController(
            CatalogDictionaryService dictionary, LoginSessionService sessions) {
        this.dictionary = dictionary;
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "读取品牌、系列和颜色及其引用状态")
    public List<CatalogDictionaryEntry> list(HttpServletRequest request) {
        return dictionary.list(sessions.requireLogin(request).user());
    }

    @PostMapping("/{type}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "新增字典项", description = "类型为 brand、series、color。新增系列须传所属品牌 parentId。")
    public void create(
            @PathVariable Type type, @Valid @RequestBody Create body, HttpServletRequest request) {
        dictionary.create(sessions.requireLogin(request).user(), type, body);
    }

    @PutMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "给未被引用的字典项改名", description = "expectedName 为读取时的名称。")
    public void rename(
            @PathVariable Type type,
            @PathVariable long id,
            @Valid @RequestBody Rename body,
            HttpServletRequest request) {
        dictionary.rename(sessions.requireLogin(request).user(), type, id, body);
    }

    @DeleteMapping("/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "删除未被引用的字典项",
            description = "expectedName 为读取时的名称。删除系列时会同时删除其下未被商品和政策引用的机型。")
    public void delete(
            @PathVariable Type type,
            @PathVariable long id,
            @RequestParam String expectedName,
            HttpServletRequest request) {
        dictionary.delete(sessions.requireLogin(request).user(), type, id, expectedName);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(new ApiError("CATALOG_DICTIONARY_INVALID", "字典类型或参数无效。"));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CATALOG_DICTIONARY_CONFLICT", "字典项正在更新，请刷新后操作。"));
    }
}
