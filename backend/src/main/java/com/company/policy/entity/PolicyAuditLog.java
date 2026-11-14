package com.company.policy.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PolicyAuditLog {
    private Long id;
    private int version;
    private String auditStatus;
    private String auditorName;
    private String auditComment;
    private LocalDateTime auditTime;
    private LocalDateTime createdAt;
    private String snapshotData;
}
