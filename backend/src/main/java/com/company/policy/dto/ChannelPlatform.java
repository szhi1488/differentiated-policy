package com.company.policy.dto;

import com.company.policy.support.policy.PolicyFormValidation;
import java.util.List;
import java.util.stream.Stream;

public record ChannelPlatform(String perspective, String platformCode) {
    public static List<ChannelPlatform> all() {
        return Stream.concat(
                        PolicyFormValidation.RETAIL.stream()
                                .map(code -> new ChannelPlatform("RETAIL", code)),
                        PolicyFormValidation.BILLION.stream()
                                .map(code -> new ChannelPlatform("BILLION", code)))
                .toList();
    }

    public boolean valid() {
        return platformCode != null
                && (("RETAIL".equals(perspective)
                                && PolicyFormValidation.RETAIL.contains(platformCode))
                        || ("BILLION".equals(perspective)
                                && PolicyFormValidation.BILLION.contains(platformCode)));
    }
}
