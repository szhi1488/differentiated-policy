package com.company.policy.controller;

import com.company.policy.service.AttachmentService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.vo.AttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/attachments")
@Tag(
        name = "政策附件",
        description = "需登录；写请求需可信 Origin 和 X-Policy-Request: 1。独立接口仅上传者可操作，已引用文件禁止物理删除。")
public class AttachmentController {
    private final AttachmentService attachments;
    private final LoginSessionService sessions;

    public AttachmentController(AttachmentService attachments, LoginSessionService sessions) {
        this.attachments = attachments;
        this.sessions = sessions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "上传单个附件",
            description =
                    "file 必填，非空且不超过 20MiB；支持 pdf/doc/docx/xls/xlsx/png/jpg/jpeg，校验扩展名与文件头（现代 Office 另校验包结构）。201 成功；400 参数或类型错误，401 未登录，403 账号停用，413 超限，503 存储不可用。")
    public ResponseEntity<AttachmentResponse> upload(
            @RequestPart("file") MultipartFile file, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachments.upload(file, sessions.requireLogin(request).user()));
    }

    @GetMapping("/{id}/content")
    @Operation(
            summary = "下载本人附件",
            description = "强制下载，不公开映射 data 目录。401 未登录，403 账号停用，404 不存在或非本人/跨租户，503 存储异常。")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest request) {
        var download = attachments.download(id, sessions.requireLogin(request).user());
        return content(download);
    }

    static ResponseEntity<Resource> content(AttachmentService.Download download) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        download.attachment().originalName(),
                                        StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.attachment().size())
                .body(download.resource());
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "移除本人独立附件",
            description =
                    "物理删除没有当前表单或历史快照引用的本人附件；204 成功，401 未登录，403 账号停用，404 不存在或非本人/跨租户，409 已被政策引用，503 存储异常。")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        attachments.delete(id, sessions.requireLogin(request).user());
        return ResponseEntity.noContent().build();
    }
}
