package com.company.policy.mapper;

import com.company.policy.entity.CatalogOperationLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogOperationLogMapper {
    @Insert(
            """
        INSERT INTO catalog_operation_log
          (actor_type, operator_id, operator_name, entity_type, entity_id, action_type,
           batch_key, before_data, after_data, reason)
        VALUES (#{actorType}, #{operatorId,jdbcType=BIGINT}, #{operatorName,jdbcType=VARCHAR},
          #{entityType}, #{entityId}, #{actionType}, #{batchKey,jdbcType=VARCHAR},
          #{beforeData,jdbcType=VARCHAR}, #{afterData,jdbcType=VARCHAR}, #{reason,jdbcType=VARCHAR})
        """)
    int insert(CatalogOperationLog entry);
}
