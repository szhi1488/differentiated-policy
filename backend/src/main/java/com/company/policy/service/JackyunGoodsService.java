package com.company.policy.service;

import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.vo.JackyunGoodsPage;

/** 按品牌和游标分页读取吉客云货品，返回的游标由同步任务在该页数据成功入库后推进。 */
public interface JackyunGoodsService {
    /** 按品牌、游标和查询阶段读取一页吉客云货品，返回商品和下一页游标。 */
    JackyunGoodsPage queryPage(JackyunGoodsQuery query);
}
