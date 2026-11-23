package com.company.policy.dto;

import static com.company.policy.support.policy.PolicyFormValidation.invalid;

import com.company.policy.support.policy.PolicyFormValidation;
import java.time.LocalDate;

/** 两个工作台共用的机型补贴查询条件，渠道和平台仅用于缩小已授权范围。 */
public record PolicyTimelineQuery(
        long spuId,
        String ram,
        String rom,
        LocalDate dateFrom,
        LocalDate dateTo,
        String perspective,
        String platformCode) {
    public void validate() {
        if (spuId < 1
                || !PolicyFormValidation.validMemory(ram)
                || !PolicyFormValidation.validMemory(rom)) {
            throw invalid("请选择有效的机型及 RAM、ROM 配置。");
        }
        if (dateFrom == null
                || dateTo == null
                || dateFrom.isAfter(dateTo)
                || dateFrom.getYear() < 1
                || dateTo.getYear() > 9999) {
            throw invalid("查询起止日期无效。");
        }
        if (perspective != null && !new ChannelPlatform(perspective, platformCode).valid()) {
            throw invalid("请选择有效的渠道及平台。");
        }
        if (platformCode != null && !PolicyFormValidation.RETAIL.contains(platformCode)) {
            throw invalid("平台筛选值无效。");
        }
    }

    public boolean matches(ChannelPlatform scope) {
        return (perspective == null || perspective.equals(scope.perspective()))
                && (platformCode == null || platformCode.equals(scope.platformCode()));
    }
}
