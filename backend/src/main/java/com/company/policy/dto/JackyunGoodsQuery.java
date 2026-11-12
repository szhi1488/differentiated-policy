package com.company.policy.dto;

import java.time.LocalDate;

/** 查询一个品牌的一页货品，游标从 0 开始。 */
public record JackyunGoodsQuery(
        String brandName,
        int pageSize,
        String lastMaxId,
        boolean includeBlockup,
        Phase phase,
        LocalDate date) {

    public JackyunGoodsQuery(
            String brandName, int pageSize, String lastMaxId, boolean includeBlockup) {
        this(brandName, pageSize, lastMaxId, includeBlockup, Phase.FULL, null);
    }

    public enum Phase {
        FULL("", ""),
        CREATED("startDate", "endDate"),
        GOODS_MODIFIED("startDateModifiedGoods", "endDateModifiedGoods"),
        SKU_MODIFIED("startDateModifiedSku", "endDateModifiedSku");

        public final String startKey;
        public final String endKey;

        Phase(String startKey, String endKey) {
            this.startKey = startKey;
            this.endKey = endKey;
        }
    }

    public JackyunGoodsQuery {
        if (phase == null || (phase == Phase.FULL) != (date == null)) {
            throw new IllegalArgumentException("查询阶段与导入日期不匹配");
        }
        if (!"一加手机".equals(brandName) && !"OPPO手机".equals(brandName)) {
            throw new IllegalArgumentException("只支持查询一加手机和OPPO手机");
        }
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("吉客云查询页大小须为 1–50");
        }
        if (lastMaxId == null || !lastMaxId.matches("[0-9]{1,19}")) {
            throw new IllegalArgumentException("吉客云查询游标须为非负整数");
        }
        try {
            lastMaxId = Long.toString(Long.parseLong(lastMaxId));
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("吉客云查询游标超出范围");
        }
    }
}
