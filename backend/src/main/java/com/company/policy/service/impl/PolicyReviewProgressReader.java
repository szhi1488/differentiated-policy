package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;

import com.company.policy.mapper.PolicyReviewRoundMapper;
import com.company.policy.mapper.PolicyReviewRoundMapper.ProgressRow;
import com.company.policy.vo.PolicyReviewRoundResponse.Member;
import com.company.policy.vo.PolicyReviewRoundResponse.Progress;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class PolicyReviewProgressReader {
    private final PolicyReviewRoundMapper rounds;

    /** 调用方先核实单据及轮次的阅读权限。只投影该轮提交和审核信息。 */
    Progress read(String tenant, long id, int version) {
        var row = rounds.progress(tenant, id, version);
        return row == null ? null : read(tenant, id, version, row);
    }

    Progress read(String tenant, long id, int version, ProgressRow row) {
        var members = rounds.members(tenant, id, version);
        boolean pending = "SUBMITTED".equals(row.auditStatus());
        String rejectedAtStage =
                members.stream()
                        .filter(m -> "REJECT".equals(m.decision()))
                        .map(
                                m ->
                                        "PRODUCT".equals(m.groupCode())
                                                ? "PRODUCT_REVIEW"
                                                : "PURCHASE_REVIEW")
                        .findFirst()
                        .orElse(null);
        return new Progress(
                version,
                pending ? "PENDING" : row.auditStatus(),
                pending ? row.stage() : null,
                rejectedAtStage,
                row.applicantName(),
                formatTime(row.submittedAt()),
                row.requiresProductReview(),
                members(members, "PRODUCT"),
                members(members, "PURCHASE"));
    }

    private static List<Member> members(List<PolicyReviewRoundMapper.Member> all, String group) {
        return all.stream()
                .filter(m -> group.equals(m.groupCode()))
                .map(
                        m ->
                                new Member(
                                        Long.toString(m.id()),
                                        Long.toString(m.userId()),
                                        m.displayName(),
                                        m.department(),
                                        m.assignmentNo(),
                                        m.memberStatus(),
                                        m.decision(),
                                        m.reason(),
                                        formatTime(m.decidedAt())))
                .toList();
    }
}
