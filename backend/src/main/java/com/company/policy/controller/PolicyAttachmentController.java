package com.company.policy.controller;

import com.company.policy.service.AttachmentService;
import com.company.policy.service.LoginSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiResponse(responseCode = "400", description = "申请或审核记录 ID 格式无效")
@Tag(name = "政策附件下载", description = "校验有效身份、父申请权限和文件所属表单或提交轮次。401 未登录，403 身份失效，404 无权访问或文件不属于所查内容。")
public class PolicyAttachmentController {
    private final AttachmentService attachments;
    private final LoginSessionService sessions;

    public PolicyAttachmentController(AttachmentService attachments, LoginSessionService sessions) {
        this.attachments = attachments;
        this.sessions = sessions;
    }

    @GetMapping("/api/policies/{id}/attachments/{attachmentId}/content")
    @Operation(summary = "下载本人申请当前内容的附件")
    public ResponseEntity<Resource> current(
            @PathVariable long id, @PathVariable String attachmentId, HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return AttachmentController.content(attachments.downloadCurrent(id, attachmentId, user));
    }

    @GetMapping("/api/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content")
    @Operation(summary = "下载本人申请指定提交轮次的附件")
    public ResponseEntity<Resource> history(
            @PathVariable long id,
            @PathVariable long logId,
            @PathVariable String attachmentId,
            HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return AttachmentController.content(
                attachments.downloadApplicantHistory(id, logId, attachmentId, user));
    }

    @GetMapping("/api/policies/shared/{id}/attachments/{attachmentId}/content")
    @Operation(summary = "下载本人负责平台最新正式轮次引用的附件")
    public ResponseEntity<Resource> shared(
            @PathVariable long id,
            @PathVariable String attachmentId,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return AttachmentController.content(
                attachments.downloadShared(
                        id,
                        submissionVersion,
                        attachmentId,
                        sessions.requireLogin(request).user()));
    }

    @GetMapping("/api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content")
    @Operation(summary = "管理员下载指定申请提交轮次的附件")
    public ResponseEntity<Resource> admin(
            @PathVariable long id,
            @PathVariable long logId,
            @PathVariable String attachmentId,
            HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return AttachmentController.content(
                attachments.downloadAdminHistory(id, logId, attachmentId, user));
    }

    @GetMapping(
            "/api/policy-reviews/{id}/time-changes/{changeId}/attachments/{attachmentId}/content")
    @Operation(summary = "下载原政策指定调整记录的证明文件")
    public ResponseEntity<Resource> timeChange(
            @PathVariable long id,
            @PathVariable long changeId,
            @PathVariable String attachmentId,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return AttachmentController.content(
                attachments.downloadTimeChange(
                        id,
                        submissionVersion,
                        changeId,
                        attachmentId,
                        sessions.requireLogin(request).user()));
    }

    @GetMapping("/api/policy-reviews/{id}/attachments/{attachmentId}/content")
    @Operation(summary = "下载审核详情中指定轮次的附件", description = "submissionVersion 为提交轮次，文件须被本轮快照引用。")
    public ResponseEntity<Resource> reviewRound(
            @PathVariable long id,
            @PathVariable String attachmentId,
            @RequestParam int submissionVersion,
            HttpServletRequest request) {
        return AttachmentController.content(
                attachments.downloadReviewRound(
                        id,
                        submissionVersion,
                        attachmentId,
                        sessions.requireLogin(request).user()));
    }
}
