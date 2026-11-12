package com.company.policy.entity;

import com.company.policy.vo.JackyunGoodsPage.Goods;
import lombok.Data;

@Data
public class SourceGoodsItem {
    private Long id;
    private String brandName;
    private String goodsName;
    private String goodsNo;
    private String skuName;
    private String cateName;
    private String goodsField4;
    private String goodsField1;
    private String goodsField5;
    private int collisionNo;
    private Long lastSeenJobId;
    private String cleanStatus;

    public Goods original() {
        return new Goods(
                goodsName, goodsNo, skuName, cateName, goodsField4, goodsField1, goodsField5);
    }
}
