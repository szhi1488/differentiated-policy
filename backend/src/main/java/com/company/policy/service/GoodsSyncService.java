package com.company.policy.service;

import com.company.policy.dto.GoodsSyncRequest;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.GoodsSyncResponse;
import com.company.policy.vo.GoodsSyncResponse.Overview;
import com.company.policy.vo.GoodsSyncResponse.SourcePage;
import java.time.LocalDate;

/** 管理吉客云源商品的分页导入和断点续跑，并提供已导入商品查询。 */
public interface GoodsSyncService {
    /** 查询当前账套是否可导入，以及各品牌最近一次导入任务的状态。 */
    Overview overview(CurrentUserResponse session);

    /** 按指定品牌、导入模式和日期发起源商品导入任务。 */
    GoodsSyncResponse start(CurrentUserResponse session, GoodsSyncRequest request);

    /** 核对版本后，从已保存的断点继续暂停的导入任务。 */
    GoodsSyncResponse resume(CurrentUserResponse session, long id, int version);

    /** 核对版本后结束已暂停的导入任务。 */
    GoodsSyncResponse cancel(CurrentUserResponse session, long id, int version);

    /** 按品牌、建档日期和关键词分页查询已入库的源商品。 */
    SourcePage sources(
            CurrentUserResponse session,
            String brand,
            String keyword,
            LocalDate createdDate,
            int page,
            int size);

    /** 每次最多处理一页源商品，成功入库后保存导入进度和续拉游标。 */
    void processNextPage();
}
