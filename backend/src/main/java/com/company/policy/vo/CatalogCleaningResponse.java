package com.company.policy.vo;

import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class CatalogCleaningResponse {
    private CatalogCleaningResponse() {}

    public enum Status {
        PENDING,
        PROCESSING,
        AUTO_PASSED,
        REVIEW_REQUIRED,
        MANUAL_PASSED,
        ERROR,
        EXCLUDED
    }

    public record Job(
            String id,
            String brandName,
            LocalDate createdDate,
            boolean includeReviews,
            String targetSourceId,
            String status,
            boolean legacy,
            String requesterName,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
            long total,
            long processed,
            int progress,
            Map<String, Long> resultCounts) {}

    public record Candidate(
            String seriesName,
            String modelName,
            String spec,
            String colorName,
            List<String> colorCandidates,
            String edition,
            String displayGoodsName,
            String displaySkuName,
            String code,
            String explanation,
            String suggestedAction,
            List<Evidence> evidence) {
        public Candidate {
            colorCandidates =
                    colorCandidates == null
                            ? (colorName == null ? List.of() : List.of(colorName))
                            : List.copyOf(colorCandidates);
        }
    }

    public record Evidence(String field, String value) {}

    public record Result(
            String id,
            String brandName,
            String goodsName,
            String goodsNo,
            String skuName,
            LocalDate createdDate,
            String status,
            int recordVersion,
            String seriesName,
            String modelName,
            String spec,
            String colorName,
            List<String> colorCandidates,
            String evidence,
            String issueCode,
            String suggestedAction,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime cleanedAt) {}

    public record Detail(
            Result result,
            Goods original,
            Candidate candidate,
            String mappingStatus,
            String catalogVariantId,
            String variantColorId) {}

    public record Log(
            String id,
            String sourceId,
            String actorType,
            String operatorName,
            String action,
            String reason,
            JsonNode before,
            JsonNode after,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt) {}

    public record Page<T>(List<T> items, long total) {}

    public record Results(List<Result> items, long total, Map<String, Long> counts) {}

    public record Preview(List<Result> items, long total, long eligible) {}
}
