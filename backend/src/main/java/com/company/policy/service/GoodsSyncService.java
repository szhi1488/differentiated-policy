package com.company.policy.service;

import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.GoodsSyncResponse;
import com.company.policy.vo.GoodsSyncResponse.Overview;
import com.company.policy.vo.GoodsSyncResponse.SourcePage;
import java.time.LocalDate;

public interface GoodsSyncService {
    Overview overview(CurrentUserResponse session);

    GoodsSyncResponse start(CurrentUserResponse session, GoodsSyncRequest request);

    GoodsSyncResponse resume(CurrentUserResponse session, long id, int version);

    GoodsSyncResponse cancel(CurrentUserResponse session, long id, int version);

    SourcePage sources(
            CurrentUserResponse session,
            String brand,
            String keyword,
            LocalDate createdDate,
            int page,
            int size);

    /** 后台最多领取并处理一页。 */
    void processNextPage();
}
