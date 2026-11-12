package com.company.policy.service;

import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Log;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Result;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import com.company.policy.vo.CurrentUserResponse;

public interface CatalogCleaningService {
    Job latest(CurrentUserResponse session, String brand);

    Job job(CurrentUserResponse session, long id);

    Job start(CurrentUserResponse session, long id, int version, boolean retryErrors);

    Job reprocessReviews(CurrentUserResponse session, long id, int version);

    Page<Result> preview(
            CurrentUserResponse session, long id, String ruleVersion, int page, int size);

    Page<Result> results(
            CurrentUserResponse session,
            Long jobId,
            String brand,
            Status status,
            String ruleVersion,
            String keyword,
            int page,
            int size);

    Detail detail(CurrentUserResponse session, long id);

    Detail review(CurrentUserResponse session, long id, Review request);

    Job retrySource(CurrentUserResponse session, long id, int version);

    Page<Log> logs(CurrentUserResponse session, long id, int page, int size);

    void processNextBatch();
}
