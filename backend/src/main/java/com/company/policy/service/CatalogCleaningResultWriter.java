package com.company.policy.service;

import com.company.policy.common.exception.ApiException;
import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import com.company.policy.vo.CatalogCleaningResponse.Status;
import org.springframework.http.HttpStatus;

/** 人工审核和自动清洗共用的目录映射与结果写入，写操作沿用调用方事务。 */
public interface CatalogCleaningResultWriter {
    /** 根据清洗候选规格创建或复用标准商品，返回源商品与标准商品的映射。 */
    Mapping resolve(SourceGoodsItem source, Candidate candidate, String origin);

    /** 在当前事务中保存源商品的清洗结果，并按传入的映射更新商品关联。 */
    void persist(
            CatalogCleaningJob job,
            SourceGoodsItem source,
            Candidate candidate,
            Status status,
            Mapping mapping,
            UserInfo operator,
            String reason);

    /** 读取源商品上次保存的候选识别结果，尚无结果时返回空值。 */
    Candidate readCandidate(SourceGoodsItem source);

    record Mapping(String status, String origin, Long brandId, Long goodsId) {
        public static final Mapping EXCLUDED = new Mapping("EXCLUDED", null, null, null);
    }

    /** 目录写入前发现的业务冲突，允许调用方将商品转入人工审核。 */
    final class MappingConflict extends ApiException {
        public MappingConflict(String code, String reason) {
            super(HttpStatus.CONFLICT, code, reason);
        }
    }
}
