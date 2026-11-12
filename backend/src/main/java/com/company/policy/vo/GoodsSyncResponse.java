package com.company.policy.vo;

import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GoodsSyncResponse(
        String id,
        String brandName,
        @Schema(allowableValues = {"QUEUED", "RUNNING", "PAUSED", "SUCCEEDED", "CANCELLED"})
                String status,
        String cleanStatus,
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
                job.getStatus(),
                job.getCleanStatus(),
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
                    item.getCleanStatus());
        }
    }

    public record SourcePage(List<Source> items, long total) {}
}
