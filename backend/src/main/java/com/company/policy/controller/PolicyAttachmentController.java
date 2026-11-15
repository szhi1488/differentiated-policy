package com.company.policy.controller;

import com.company.policy.common.exception.ApiException;
import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.service.AttachmentService;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.vo.CurrentUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "政策附件下载", description = "校验有效身份、父申请权限和文件所属表单或提交轮次。401 未登录，403 身份失效，404 无权访问或文件不属于所查内容。")
public class PolicyAttachmentController {
    private final AttachmentService attachments;
    private final LoginSessionService sessions;
    private final PolicyApplyService policies;
    private final PolicyReviewService reviews;

    public PolicyAttachmentController(
            AttachmentService attachments,
            LoginSessionService sessions,
            PolicyApplyService policies,
            PolicyReviewService reviews) {
        this.attachments = attachments;
        this.sessions = sessions;
        this.policies = policies;
        this.reviews = reviews;
    }

    @GetMapping("/api/policies/{id}/attachments/{attachmentId}/content")
    @Operation(summary = "下载本人申请当前内容的附件")
    public ResponseEntity<Resource> current(
            @PathVariable long id, @PathVariable String attachmentId, HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return download(policies.detail(user, id).form(), attachmentId, user);
    }

    @GetMapping("/api/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content")
    @Operation(summary = "下载本人申请指定提交轮次的附件")
    public ResponseEntity<Resource> history(
            @PathVariable long id,
            @PathVariable long logId,
            @PathVariable String attachmentId,
            HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return download(
                policies.auditDetail(user, id, logId).snapshot().form(), attachmentId, user);
    }

    @GetMapping("/api/admin/policies/{id}/audit-logs/{logId}/attachments/{attachmentId}/content")
    @Operation(summary = "管理员下载指定申请提交轮次的附件")
    public ResponseEntity<Resource> admin(
            @PathVariable long id,
            @PathVariable long logId,
            @PathVariable String attachmentId,
            HttpServletRequest request) {
        var user = sessions.requireLogin(request).user();
        return download(reviews.auditDetail(user, id, logId).snapshot().form(), attachmentId, user);
    }

    private ResponseEntity<Resource> download(
            PolicyDraftRequest form, String id, CurrentUserResponse user) {
        if (form.attachmentInfo().stream()
                .noneMatch(item -> "FILE".equals(item.type()) && id.equals(item.id()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问。");
        }
        return AttachmentController.content(attachments.downloadForPolicy(id, user));
    }
}
