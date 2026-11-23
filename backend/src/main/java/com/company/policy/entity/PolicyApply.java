package com.company.policy.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PolicyApply {
    private Long id;
    private String policyNo;
    private Long extensionSourceId;
    private boolean adjustmentOnly;
    private String tenantKey;
    private Long applicantId;
    private String applicantName;
    private String channelType;
    private String platforms;
    private String writeOffType;
    private String sceneTypes;
    private Boolean isEstimated;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String remark;
    private String goodsFormJson;
    private String attachmentUrls;
    private String status;
    private String reviewStage;
    private String productReviewerName;
    private LocalDateTime productReviewedAt;
    private int currentVersion;
    private int recordVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
