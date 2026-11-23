package com.company.policy.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PolicyAuditLog {
    private Long id;
    private Long policyId;
    private int version;
    private String auditStatus;
    private int workflowVersion;
    private String rejectedAtStage;
    private String auditorName;
    private String auditComment;
    private LocalDateTime auditTime;
    private LocalDateTime createdAt;
    private String snapshotData;
}
