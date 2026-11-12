package com.company.policy.mapper;

import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogCleaningMapper {
    String SCOPE = "tenant_key = #{tenant} AND source_key = #{account}";
    String BATCH = " AND (last_seen_job_id = #{jobId} OR clean_job_id = #{jobId})";
    String SOURCE = "SELECT * FROM source_goods_item WHERE " + SCOPE + " AND id = #{id}";
    String FILTER =
            """
            FROM source_goods_item WHERE tenant_key = #{query.tenant} AND source_key = #{query.account}
            <if test="query.jobId != null">
              AND (last_seen_job_id = #{query.jobId} OR clean_job_id = #{query.jobId})
            </if>
            <if test="query.brand != null">AND brand_name = #{query.brand}</if>
            <if test="query.status != null">AND clean_status = #{query.status}</if>
            <if test="query.version != null">AND clean_rule_version = #{query.version}</if>
            <if test="query.keyword != null">
              AND (LOCATE(#{query.keyword}, goods_name) > 0 OR LOCATE(#{query.keyword}, goods_no) > 0)
            </if>
            """;

    @Select(
            "<script> SELECT * FROM goods_sync_job WHERE "
                    + SCOPE
                    + """
            AND status = 'SUCCEEDED'
            <if test="brand != null">AND brand_name = #{brand}</if>
            ORDER BY id DESC LIMIT 1
            </script>
            """)
    GoodsSyncJob latest(String tenant, String account, String brand);

    @Select(
            "SELECT * FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND status = 'SUCCEEDED' AND id = #{id}")
    GoodsSyncJob job(String tenant, String account, long id);

    @Select(
            "SELECT * FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND status = 'SUCCEEDED' AND id = #{id} FOR UPDATE")
    GoodsSyncJob lockJob(String tenant, String account, long id);

    @Update(
            """
            UPDATE goods_sync_job SET clean_status = 'RUNNING', clean_rule_version = #{version},
              worker_token = NULL, lease_until = NULL, record_version = record_version + 1
            WHERE id = #{job.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND status = 'SUCCEEDED' AND record_version = #{job.recordVersion}
            """)
    int start(GoodsSyncJob job, String version);

    @Select(
            "SELECT * FROM goods_sync_job WHERE "
                    + SCOPE
                    + """
             AND status = 'SUCCEEDED' AND clean_status = 'RUNNING'
             AND clean_rule_version IN (#{version}, #{previousVersion}, #{legacyVersion})
             AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
             ORDER BY id LIMIT 1
            """)
    GoodsSyncJob due(
            String tenant,
            String account,
            String version,
            String previousVersion,
            String legacyVersion);

    @Update(
            """
            UPDATE goods_sync_job SET worker_token = #{token},
              lease_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP)
            WHERE id = #{job.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND status = 'SUCCEEDED' AND clean_status = 'RUNNING'
              AND clean_rule_version = #{job.cleanRuleVersion} AND record_version = #{job.recordVersion}
              AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
            """)
    int claim(GoodsSyncJob job, String token);

    String LEASE =
            """
            id = #{job.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
            AND status = 'SUCCEEDED' AND clean_status = 'RUNNING' AND worker_token = #{token}
            AND clean_rule_version = #{job.cleanRuleVersion} AND record_version = #{job.recordVersion}
            AND lease_until > CURRENT_TIMESTAMP
            """;

    @Select("SELECT id FROM goods_sync_job WHERE " + LEASE + " FOR UPDATE")
    Long lockClaim(GoodsSyncJob job, String token);

    @Update(
            """
            UPDATE goods_sync_job SET worker_token = NULL, lease_until = NULL, clean_status = #{status},
              record_version = record_version + CASE WHEN #{status} = 'RUNNING' THEN 0 ELSE 1 END
            WHERE
            """
                    + LEASE)
    int release(GoodsSyncJob job, String token, String status);

    @Select(
            """
            SELECT * FROM source_goods_item
            WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName} AND last_seen_job_id = #{job.id}
              AND clean_status = 'PENDING'
              AND mapping_status <> 'EXCLUDED'
            ORDER BY id LIMIT #{limit}
            """)
    List<SourceGoodsItem> pending(GoodsSyncJob job, int limit);

    @Select(
            """
            SELECT * FROM source_goods_item
            WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName} AND last_seen_job_id = #{job.id}
            ORDER BY id LIMIT #{limit} OFFSET #{offset}
            """)
    List<SourceGoodsItem> preview(GoodsSyncJob job, int limit, long offset);

    @Select(
            """
            SELECT COUNT(*) FROM source_goods_item
            WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName} AND last_seen_job_id = #{job.id}
            """)
    long countPreview(@Param("job") GoodsSyncJob job);

    @Select(
            "SELECT clean_status AS status, COUNT(*) AS total FROM source_goods_item WHERE "
                    + SCOPE
                    + BATCH
                    + " GROUP BY clean_status")
    List<Count> counts(String tenant, String account, long jobId);

    @Update(
            """
            <script>
            UPDATE source_goods_item SET clean_status = 'PENDING', record_version = record_version + 1
            WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName} AND last_seen_job_id = #{job.id} AND clean_job_id = #{job.id}
              AND clean_status = 'ERROR'
            <if test="sourceId != null">AND id = #{sourceId}</if>
            </script>
            """)
    int resetErrors(GoodsSyncJob job, Long sourceId);

    String REPROCESSABLE =
            """
            tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
            AND brand_name = #{job.brandName} AND last_seen_job_id = #{job.id}
            AND clean_status = 'REVIEW_REQUIRED' AND mapping_status = 'UNMAPPED'
            AND mapping_origin IS NULL
            AND (clean_rule_version IS NULL OR clean_rule_version IN (#{previousVersion}, #{legacyVersion}))
            """;

    @Select("SELECT COUNT(*) FROM source_goods_item WHERE " + REPROCESSABLE)
    long countReprocessable(GoodsSyncJob job, String previousVersion, String legacyVersion);

    @Update(
            "UPDATE source_goods_item SET clean_status = 'PENDING', record_version = record_version + 1 WHERE "
                    + REPROCESSABLE)
    int resetReviews(GoodsSyncJob job, String previousVersion, String legacyVersion);

    @Select(SOURCE)
    SourceGoodsItem source(String tenant, String account, long id);

    @Select(SOURCE + " FOR UPDATE")
    SourceGoodsItem lockSource(String tenant, String account, long id);

    @Select(
            "<script> SELECT * "
                    + FILTER
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<SourceGoodsItem> results(@Param("query") Query query, int limit, long offset);

    @Select("<script> SELECT COUNT(*) " + FILTER + " </script>")
    long count(@Param("query") Query query);

    @Update(
            """
            <script>
            UPDATE source_goods_item SET clean_status = #{change.status}, clean_job_id = #{job.id},
              clean_rule_version = #{change.ruleVersion}, clean_candidate = #{change.candidate},
              clean_error_code = #{change.code}, cleaned_at = CURRENT_TIMESTAMP,
              clean_run_key = NULL, clean_lease_until = NULL, record_version = record_version + 1,
              <choose>
                <when test="reviewer != null">
                  reviewed_by = #{reviewer.id}, reviewed_at = CURRENT_TIMESTAMP, review_reason = #{change.reason},
                </when>
                <otherwise>clean_attempts = clean_attempts + 1,</otherwise>
              </choose>
              <if test="change.mappingStatus != null">
                mapping_status = #{change.mappingStatus}, mapping_origin = #{change.origin},
                catalog_variant_id = #{change.variantId}, variant_color_id = #{change.colorId},
              </if>
              mapping_remark = #{change.reason}
            WHERE id = #{source.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName} AND last_seen_job_id = #{source.lastSeenJobId}
              AND record_version = #{source.recordVersion} AND clean_status = #{source.cleanStatus}
            </script>
            """)
    int save(GoodsSyncJob job, SourceGoodsItem source, Change change, UserInfo reviewer);

    @Select(
            """
            SELECT id, series_name, status FROM catalog_variant
            WHERE tenant_key = #{tenant} AND brand_name = #{brand}
              AND model_name = #{candidate.modelName} AND spec = #{candidate.spec}
            FOR UPDATE
            """)
    Variant variant(String tenant, String brand, Candidate candidate);

    @Insert(
            """
            INSERT INTO catalog_variant
              (tenant_key, variant_code, brand_name, series_name, model_name, spec, display_name, status)
            VALUES (#{tenant}, #{code}, #{brand}, #{candidate.seriesName}, #{candidate.modelName},
              #{candidate.spec}, #{display}, 'ACTIVE')
            """)
    int insertVariant(
            String tenant, String brand, Candidate candidate, String code, String display);

    @Select(
            """
            SELECT id, status FROM catalog_variant_color
            WHERE tenant_key = #{tenant} AND variant_id = #{variantId} AND color_name = #{colorName}
            FOR UPDATE
            """)
    Color color(String tenant, long variantId, String colorName);

    @Insert(
            """
            INSERT INTO catalog_variant_color (tenant_key, variant_id, color_name, status)
            VALUES (#{tenant}, #{variantId}, #{colorName}, 'ACTIVE')
            """)
    int insertColor(String tenant, long variantId, String colorName);

    @Insert(
            """
            <script>
            INSERT INTO catalog_operation_log
              (tenant_key, actor_type, operator_id, operator_name, rule_version, entity_type, entity_id,
                action_type, batch_key, before_data, after_data, reason)
            VALUES (#{job.tenantKey},
              <choose>
                <when test="operator != null">'USER', #{operator.id}, #{operator.name},</when>
                <otherwise>'SYSTEM', NULL, NULL,</otherwise>
              </choose>
              #{version}, #{entityType}, #{entityId}, #{action}, #{batch}, #{before}, #{after}, #{reason})
            </script>
            """)
    int audit(
            GoodsSyncJob job,
            UserInfo operator,
            String version,
            String entityType,
            long entityId,
            String action,
            String batch,
            String before,
            String after,
            String reason);

    String LOGS =
            """
            FROM catalog_operation_log WHERE tenant_key = #{tenant} AND batch_key = #{batch}
            AND rule_version IS NOT NULL AND entity_type IN ('SOURCE_GOODS_ITEM', 'SYNC_JOB')
            """;

    @Select(
            """
            SELECT id, entity_type, entity_id, actor_type, operator_name, action_type,
              rule_version, reason, before_data, after_data, created_at
            """
                    + LOGS
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Audit> logs(String tenant, String batch, int limit, long offset);

    @Select("SELECT COUNT(*) " + LOGS)
    long countLogs(String tenant, String batch);

    record Query(
            String tenant,
            String account,
            Long jobId,
            String brand,
            String status,
            String version,
            String keyword) {}

    record Count(String status, long total) {}

    record Change(
            String status,
            String ruleVersion,
            String candidate,
            String code,
            String reason,
            String mappingStatus,
            String origin,
            Long variantId,
            Long colorId) {}

    record Variant(long id, String seriesName, String status) {}

    record Color(long id, String status) {}

    record Audit(
            long id,
            String entityType,
            long entityId,
            String actorType,
            String operatorName,
            String action,
            String ruleVersion,
            String reason,
            String before,
            String after,
            LocalDateTime createdAt) {}
}
