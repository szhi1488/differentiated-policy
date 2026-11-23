package com.company.policy.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 公司审核设置及后台可回显的两组人员，版本 0 表示从未保存。 */
public record PolicyReviewSettings(
        int version,
        List<PolicyReviewerCandidate> productReviewers,
        List<PolicyReviewerCandidate> purchaseReviewers,
        @Schema(description = "服务端是否已填写飞书业务群配置") boolean policyChatConfigured) {}
