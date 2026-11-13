package com.company.policy.entity;

import com.company.policy.vo.JackyunGoodsPage.Goods;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SourceGoodsItem {
    private Long id;
    private String sourceKey;
    private String brandName;
    private String goodsName;
    private String goodsNo;
    private String skuName;
    private String cateName;
    private String goodsField4;
    private String goodsField1;
    private String goodsField5;
    private String skuId;
    private String cateFullName;
    private LocalDate createdDate;
    private Long gmtModified;
    private Long goodsGmtModified;
    private Boolean isBlockup;
    private Boolean isPackageGood;
    private Long lastSeenJobId;
    private String cleanStatus;
    private Long cleanJobId;
    private String cleanCandidate;
    private String cleanErrorCode;
    private String mappingStatus;
    private String mappingOrigin;
    private Long catalogBrandId;
    private Long goodsId;
    private int recordVersion;
    private LocalDateTime cleanedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;

    public Goods original() {
        return new Goods(
                goodsName,
                goodsNo,
                skuName,
                cateName,
                goodsField4,
                goodsField1,
                goodsField5,
                skuId,
                cateFullName,
                gmtModified,
                goodsGmtModified,
                isBlockup,
                isPackageGood);
    }
}
