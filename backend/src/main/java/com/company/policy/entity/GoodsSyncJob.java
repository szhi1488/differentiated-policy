package com.company.policy.entity;

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
    private String status;
    private String lastMaxId;
    private int pageSize;
    private boolean includeBlockup;
    private int pageCount;
    private long readCount;
    private long contentCount;
    private String cleanStatus;
    private String errorCode;
    private int recordVersion;
    private boolean ready;
}
