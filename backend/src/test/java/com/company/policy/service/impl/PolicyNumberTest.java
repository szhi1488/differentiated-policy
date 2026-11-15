package com.company.policy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.policy.vo.PolicyResponse.Snapshot;
import java.util.List;
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

    @Test
    void normalizesLegacySnapshotNumberWithoutChangingStoredSnapshotContent() {
        var legacy = new Snapshot("5", "LEGACY-CREATION-CODE", "2", "申请人", 2, null, List.of());
        var displayed = PolicyApplyServiceImpl.displaySnapshot(legacy);
        assertThat(displayed.policyCode()).isEqualTo("ZC-000005");
        assertThat(legacy.policyCode()).isEqualTo("LEGACY-CREATION-CODE");
        assertThat(displayed.id()).isEqualTo(legacy.id());
        assertThat(displayed.submissionVersion()).isEqualTo(legacy.submissionVersion());
        assertThat(displayed.goods()).isSameAs(legacy.goods());
    }
}
