package com.company.policy.vo;

import java.util.List;

/** 管理员预先配置的审核分工，按实际商品目录回显。 */
public record PolicyReviewSettings(int version, List<Brand> brands, boolean policyChatConfigured) {
    public record Brand(
            String brandId,
            String brandName,
            String productMode,
            PolicyReviewerCandidate purchaseReviewer,
            PolicyReviewerCandidate brandProductReviewer,
            List<Series> series) {}

    public record Series(
            String seriesId, String seriesName, PolicyReviewerCandidate productReviewer) {}
}
