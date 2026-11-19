package com.company.policy.entity;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 商品建档参数，主键由数据库生成并回填。 */
@Data
@RequiredArgsConstructor
public class CatalogGoodsInsert {
    private Long id;
    private final String skuId;
    private final String productNo;
    private final long brandId;
    private final long seriesId;
    private final long spuId;
    private final String goodsName;
    private final String policyName;
    private final String ram;
    private final String rom;
    private final long colorId;
    private final int status;
}
