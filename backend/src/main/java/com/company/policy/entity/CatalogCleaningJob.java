package com.company.policy.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CatalogCleaningJob {
    private Long id;
    private String sourceKey;
    private String brandName;
    private LocalDate createdDate;
    private String status;
    private String requestKey;
    private Long targetSourceId;
    private boolean includeReviews;
    private Long requesterId;
    private String requesterName;
    private LocalDateTime createdAt;
}
