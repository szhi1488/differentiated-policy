package com.company.policy.service;

import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.dto.CatalogCleaningRequest.Start;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Log;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogCleaningResponse.Preview;
import com.company.policy.vo.CatalogCleaningResponse.Results;
import com.company.policy.vo.CurrentUserResponse;
import java.time.LocalDate;

public interface CatalogCleaningService {
    Page<Job> jobs(CurrentUserResponse session, int page, int size);

    Job job(CurrentUserResponse session, long id);

    Job active(CurrentUserResponse session);

    Job start(CurrentUserResponse session, Start request);

    Preview preview(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean includeReviews,
            String keyword,
            int page,
            int size);

    Results results(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String status,
            String keyword,
            int page,
            int size);

    Detail detail(CurrentUserResponse session, long id);

    Detail review(CurrentUserResponse session, long id, Review request);

    Job retrySource(CurrentUserResponse session, long id, int version, String requestKey);

    Page<Log> logs(CurrentUserResponse session, long id, int page, int size);

    Page<Log> sourceLogs(CurrentUserResponse session, long id, int page, int size);

    void processNextBatch();
}
