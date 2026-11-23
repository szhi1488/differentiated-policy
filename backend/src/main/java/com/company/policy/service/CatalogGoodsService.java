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

/** 维护标准商品目录，并为申请人提供在售商品和选品筛选项。 */
public interface CatalogGoodsService {
    /** 使用已有品牌、系列和颜色手工创建标准商品。 */
    CatalogGoodsResponse create(CurrentUserResponse session, CatalogGoodsWrite request);

    /** 核对编辑版本和引用限制后，更新指定标准商品。 */
    CatalogGoodsResponse update(CurrentUserResponse session, long id, CatalogGoodsUpdate request);

    /** 永久删除未关联来源且未被政策引用的标准商品。 */
    void delete(CurrentUserResponse session, long id);

    /** 按筛选条件分页查询管理员可见的标准商品。 */
    PageResponse<CatalogGoodsResponse> list(CurrentUserResponse session, CatalogGoodsQuery query);

    /** 按选品条件分页查询申请人可选的在售商品。 */
    PageResponse<ApplicantCatalogResponse> onSale(
            CurrentUserResponse session, CatalogGoodsQuery query);

    /** 按已选品牌、系列和机型返回在售商品的联动筛选选项。 */
    Options onSaleOptions(CurrentUserResponse session, Long brandId, Long seriesId, Long spuId);

    /** 为申请人的机型补贴查询列出所有销售状态的机型配置。 */
    Options subsidyOptions(CurrentUserResponse session);

    /** 汇总标准商品的销售状态、系列和机型数量，并列出已有内存配置。 */
    Summary summary(CurrentUserResponse session, boolean includeModelConfigurations);

    /** 按商品编号读取标准商品详情和当前编辑版本。 */
    CatalogGoodsResponse detail(CurrentUserResponse session, long id);
}
