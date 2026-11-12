package com.company.policy.vo;

import java.util.List;

/** 后端同步使用的一页货品，保留来源字段和当前续拉位置。 */
public record JackyunGoodsPage(String brandName, List<Goods> goods, String nextLastMaxId) {

    public JackyunGoodsPage {
        goods = List.copyOf(goods);
    }

    public record Goods(
            String goodsName,
            String goodsNo,
            String skuName,
            String cateName,
            String goodsField4,
            String goodsField1,
            String goodsField5,
            String skuId,
            String cateFullName,
            Long gmtModified,
            Long goodsGmtModified,
            Boolean isBlockup,
            Boolean isPackageGood) {}
}
