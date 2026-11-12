package com.company.policy.vo;

import com.company.policy.vo.JackyunGoodsPage.Goods;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
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
            String importJobId,
            String brandName,
            String status,
            String ruleVersion,
            int recordVersion,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
            long total,
            long processed,
            int progress,
            Map<String, Long> resultCounts,
            long reprocessable) {}

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
            String importJobId,
            String brandName,
            String goodsName,
            String goodsNo,
            String status,
            String ruleVersion,
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
            String importJobId,
            String sourceId,
            String actorType,
            String operatorName,
            String action,
            String ruleVersion,
            String reason,
            JsonNode before,
            JsonNode after,
            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt) {}

    public record Page<T>(List<T> items, long total) {}
}
