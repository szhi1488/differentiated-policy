package com.company.policy.dto;

import com.company.policy.support.policy.PolicyFormValidation;

public record ChannelPlatform(String perspective, String platformCode) {
    public boolean valid() {
        return platformCode != null
                && (("RETAIL".equals(perspective)
                                && PolicyFormValidation.RETAIL.contains(platformCode))
                        || ("BILLION".equals(perspective)
                                && PolicyFormValidation.BILLION.contains(platformCode)));
    }
}
