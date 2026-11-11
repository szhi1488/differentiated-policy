package com.company.policy.service;

import com.company.policy.dto.JackyunGoodsQuery;
import com.company.policy.vo.JackyunGoodsPage;

/** 吉客云货品的只读查询。由同步任务在可靠入库后推进返回的游标。 */
public interface JackyunGoodsService {
    JackyunGoodsPage queryPage(JackyunGoodsQuery query);
}
