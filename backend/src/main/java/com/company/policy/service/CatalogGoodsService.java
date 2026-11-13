package com.company.policy.service;

import com.company.policy.dto.CatalogGoodsCreate;
import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.vo.CatalogCleaningResponse.Page;
import com.company.policy.vo.CatalogGoodsResponse;
import com.company.policy.vo.CatalogGoodsResponse.Summary;
import com.company.policy.vo.CurrentUserResponse;

public interface CatalogGoodsService {
    CatalogGoodsResponse create(CurrentUserResponse session, CatalogGoodsCreate request);

    void disable(CurrentUserResponse session, long id);

    Page<CatalogGoodsResponse> list(CurrentUserResponse session, CatalogGoodsQuery query);

    Summary summary(CurrentUserResponse session);

    CatalogGoodsResponse detail(CurrentUserResponse session, long id);
}
