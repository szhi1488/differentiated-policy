package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PolicyNumberTest {
    @Test
    void padsSmallIdsAndKeepsLargeIdsWithoutTruncation() {
        assertThat(PolicyApplyServiceImpl.policyNumber(5)).isEqualTo("ZC-000005");
        assertThat(PolicyApplyServiceImpl.policyNumber(999999)).isEqualTo("ZC-999999");
        assertThat(PolicyApplyServiceImpl.policyNumber(1000000)).isEqualTo("ZC-1000000");
        assertThat(PolicyApplyServiceImpl.policyNumber(Long.MAX_VALUE))
                .isEqualTo("ZC-9223372036854775807");
    }
}
