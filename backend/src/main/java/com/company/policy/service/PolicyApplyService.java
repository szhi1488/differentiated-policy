package com.company.policy.service;

import com.company.policy.dto.PolicyDraftRequest;
import com.company.policy.dto.PolicyListQuery;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PolicyResponse;
import com.company.policy.vo.PolicyResponse.AuditDetail;
import com.company.policy.vo.PolicyResponse.AuditSummary;
import com.company.policy.vo.PolicyResponse.Detail;
import com.company.policy.vo.PolicyResponse.Page;
import com.company.policy.vo.PolicyResponse.Summary;

public interface PolicyApplyService {
    PolicyResponse create(
            CurrentUserResponse session, PolicyDraftRequest request, String requestKey);

    PolicyResponse save(CurrentUserResponse session, long id, PolicyDraftRequest request);

    PolicyResponse submit(CurrentUserResponse session, long id, Integer recordVersion);

    Detail detail(CurrentUserResponse session, long id);

    Page<Summary> mine(CurrentUserResponse session, PolicyListQuery query);

    Page<AuditSummary> auditLogs(CurrentUserResponse session, long id, int page, int pageSize);

    AuditDetail auditDetail(CurrentUserResponse session, long id, long logId);
}
