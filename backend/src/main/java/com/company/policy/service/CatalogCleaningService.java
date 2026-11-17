package com.company.policy.service;

import com.company.policy.dto.CatalogCleaningRequest.Review;
import com.company.policy.dto.CatalogCleaningRequest.Start;
import com.company.policy.vo.CatalogCleaningResponse.Detail;
import com.company.policy.vo.CatalogCleaningResponse.Job;
import com.company.policy.vo.CatalogCleaningResponse.Preview;
import com.company.policy.vo.CatalogCleaningResponse.Results;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PageResponse;
import java.time.LocalDate;

/** 供管理员预览和发起商品清洗、查询清洗结果，以及审核或重试异常商品。 */
public interface CatalogCleaningService {
    /** 分页查询当前账套的清洗任务及各任务的结果统计。 */
    PageResponse<Job> jobs(CurrentUserResponse session, int page, int size);

    /** 按任务编号查询清洗范围、执行进度和结果统计。 */
    Job job(CurrentUserResponse session, long id);

    /** 查询当前账套的活动清洗任务，没有活动任务时返回空值。 */
    Job active(CurrentUserResponse session);

    /** 核对管理员确认的清洗范围和数量，创建任务及待处理商品明细。 */
    Job start(CurrentUserResponse session, Start request);

    /** 分页预览所选范围内的源商品，关键词只筛选预览列表，待清洗总数按完整范围计算。 */
    Preview preview(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean includeReviews,
            String keyword,
            int page,
            int size);

    /** 按品牌、建档日期、清洗状态和关键词查询商品清洗结果。 */
    Results results(
            CurrentUserResponse session,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String status,
            String keyword,
            int page,
            int size);

    /** 按源商品编号读取清洗详情和候选识别结果。 */
    Detail detail(CurrentUserResponse session, long id);

    /** 审核待复核的源商品，通过时确认标准商品映射，不通过时将其标为已排除。 */
    Detail review(CurrentUserResponse session, long id, Review request);

    /** 为发生技术错误的源商品创建单品重试任务。 */
    Job retrySource(CurrentUserResponse session, long id, int version, String requestKey);
}
