package com.company.policy.service;

import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.dto.CatalogGoodsUpdate;
import com.company.policy.dto.CatalogGoodsWrite;
import com.company.policy.vo.ApplicantCatalogResponse;
import com.company.policy.vo.ApplicantCatalogResponse.Options;
import com.company.policy.vo.CatalogGoodsResponse;
import com.company.policy.vo.CatalogGoodsResponse.Summary;
import com.company.policy.vo.CurrentUserResponse;
import com.company.policy.vo.PageResponse;

public interface CatalogGoodsService {
    CatalogGoodsResponse create(CurrentUserResponse session, CatalogGoodsWrite request);

    CatalogGoodsResponse update(CurrentUserResponse session, long id, CatalogGoodsUpdate request);

    void delete(CurrentUserResponse session, long id);

    PageResponse<CatalogGoodsResponse> list(CurrentUserResponse session, CatalogGoodsQuery query);

    PageResponse<ApplicantCatalogResponse> onSale(
            CurrentUserResponse session, CatalogGoodsQuery query);

    Options onSaleOptions(CurrentUserResponse session, Long brandId, Long seriesId, Long spuId);

    Summary summary(CurrentUserResponse session);

    CatalogGoodsResponse detail(CurrentUserResponse session, long id);
}
