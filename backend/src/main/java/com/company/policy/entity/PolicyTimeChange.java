package com.company.policy.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** 一次已经生效的原单改期。时间、操作者及采用决定均按本次办理冻结。 */
@Data
public class PolicyTimeChange {
    private Long id;
    private Long policyId;
    private String tenantKey;
    private Integer policyVersion;
    private Long auditLogId;
    private Integer afterRecordVersion;
    private String direction;
    private LocalDateTime previousEnd;
    private LocalDateTime requestedEnd;
    private LocalDateTime actualEnd;
    private LocalDateTime effectiveAt;
    private LocalDateTime changedAt;
    private String reason;
    private Long actorId;
    private String actorName;
    private String attachmentJson;
    private String consentJson;
    private String requestKey;
    private String requestHash;
    private Long sourcePolicyId;
    private Integer sourceVersion;
}
