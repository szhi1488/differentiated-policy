package com.company.policy.vo;

import com.company.policy.dto.GoodsSyncRequest.Mode;
import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record GoodsSyncResponse(
        String id,
        String brandName,
        Mode syncMode,
        LocalDate syncDate,
        Phase syncPhase,
        @Schema(allowableValues = {"QUEUED", "RUNNING", "PAUSED", "SUCCEEDED", "CANCELLED"})
                String status,
        int pageCount,
        long readCount,
        long contentCount,
        String errorCode,
        boolean canResume,
        int recordVersion) {
    public static GoodsSyncResponse from(GoodsSyncJob job) {
        return new GoodsSyncResponse(
                job.getId().toString(),
                job.getBrandName(),
                job.getSyncMode(),
                job.getSyncDate(),
                job.getSyncPhase(),
                job.getStatus(),
                job.getPageCount(),
                job.getReadCount(),
                job.getContentCount(),
                job.getErrorCode(),
                "PAUSED".equals(job.getStatus()) && job.isReady(),
                job.getRecordVersion());
    }

    public record Overview(boolean configured, List<GoodsSyncResponse> jobs) {}

    public record Source(
            String id,
            String brandName,
            String goodsName,
            String goodsNo,
            String skuName,
            String cateName,
            String goodsField4,
            String goodsField1,
            String goodsField5,
            String skuId,
            String cateFullName,
            LocalDate createdDate,
            Long gmtModified,
            Long goodsGmtModified,
            Boolean isBlockup,
            Boolean isPackageGood,
            String cleanStatus) {
        public static Source from(SourceGoodsItem item) {
            return new Source(
                    item.getId().toString(),
                    item.getBrandName(),
                    item.getGoodsName(),
                    item.getGoodsNo(),
                    item.getSkuName(),
                    item.getCateName(),
                    item.getGoodsField4(),
                    item.getGoodsField1(),
                    item.getGoodsField5(),
                    item.getSkuId(),
                    item.getCateFullName(),
                    item.getCreatedDate(),
                    item.getGmtModified(),
                    item.getGoodsGmtModified(),
                    item.getIsBlockup(),
                    item.getIsPackageGood(),
                    item.getCleanStatus());
        }
    }

    public record SourcePage(List<Source> items, long total) {}
}
