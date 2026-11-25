package com.company.policy.service.impl;

import static com.company.policy.service.impl.PolicyApplyServiceImpl.formatTime;
import static com.company.policy.service.impl.PolicyApplyServiceImpl.policyNumber;

import com.company.policy.dto.PolicyDraftRequest.Link;
import com.company.policy.entity.PolicyTimeChange;
import com.company.policy.mapper.PolicyTimeChangeMapper;
import com.company.policy.vo.PolicyResponse.TimeChange;
import com.company.policy.vo.PolicyResponse.TimeHistory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class PolicyTimeHistory {
    private final PolicyTimeChangeMapper changes;
    private final ObjectMapper json;

    /** 调用方已校验单据和轮次的阅读权限。首次截止只从原获批快照读取。 */
    TimeHistory read(String tenant, long policyId, int version, String initialEnd) {
        var records =
                changes.history(tenant, policyId, version).stream().map(this::describe).toList();
        return new TimeHistory(
                initialEnd, records.isEmpty() ? initialEnd : records.get(0).actualEnd(), records);
    }

    private TimeChange describe(PolicyTimeChange row) {
        try {
            List<Link> files =
                    json.readValue(row.getAttachmentJson(), new TypeReference<List<Link>>() {});
            return new TimeChange(
                    row.getId().toString(),
                    row.getDirection(),
                    formatTime(row.getPreviousEnd()),
                    formatTime(row.getRequestedEnd()),
                    formatTime(row.getActualEnd()),
                    formatTime(row.getEffectiveAt()),
                    formatTime(row.getChangedAt()),
                    row.getActorName(),
                    row.getReason(),
                    row.getSourcePolicyId() == null ? null : policyNumber(row.getSourcePolicyId()),
                    files);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取时间调整的证明文件。", e);
        }
    }
}
