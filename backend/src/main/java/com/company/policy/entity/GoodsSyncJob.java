package com.company.policy.entity;

import com.company.policy.dto.GoodsSyncRequest.Mode;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 单品牌原始导入任务。执行字段只在服务端使用。 */
@Data
public class GoodsSyncJob {
    private Long id;
    private String tenantKey;
    private String sourceKey;
    private Long requesterId;
    private String brandName;
    private String idempotencyKey;
    private Mode syncMode;
    private LocalDate syncDate;
    private Phase syncPhase;
    private String status;
    private String lastMaxId;
    private int pageSize;
    private boolean includeBlockup;
    private int pageCount;
    private long readCount;
    private long contentCount;
    private LocalDateTime createdAt;
    private String errorCode;
    private int recordVersion;
    private boolean ready;
}
