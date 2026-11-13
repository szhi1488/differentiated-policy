package com.company.policy.mapper;

import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import com.company.policy.vo.CatalogCleaningResponse.Candidate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogCleaningMapper {
    String SCOPE = "tenant_key = #{tenantKey} AND source_key = #{sourceKey}";
    String SOURCE = "SELECT * FROM source_goods_item WHERE " + SCOPE + " AND id = #{id}";
    String FILTER =
            " FROM source_goods_item item WHERE "
                    + SCOPE
                    + """
         <if test="brand != null">AND item.brand_name = #{brand}</if>
         <if test="keyword != null">
           AND (LOCATE(#{keyword}, item.goods_name) > 0 OR LOCATE(#{keyword}, item.goods_no) > 0)
         </if>
        """
                    + SourceGoodsItemMapper.DATE_FILTER
                    + """
         <if test="unknownDate">
           AND NOT EXISTS (SELECT 1
        """
                    + SourceGoodsItemMapper.CREATED_DATE_LOG
                    + """
           )
           AND NOT EXISTS (SELECT 1
        """
                    + SourceGoodsItemMapper.CREATED_DATE_JOB
                    + """
           )
         </if>
        """;
    String TAB =
            """
        <choose>
          <when test="status == 'PASSED'">AND item.clean_status IN ('AUTO_PASSED', 'MANUAL_PASSED')</when>
          <when test="status == 'PENDING'">AND item.clean_status IN ('PENDING', 'PROCESSING')</when>
          <when test="status != null">AND item.clean_status = #{status}</when>
        </choose>
        """;
    String COMPLETED_IMPORT =
            """
         AND EXISTS (SELECT 1 FROM goods_sync_job synced
           WHERE synced.id = item.last_seen_job_id AND synced.status IN ('SUCCEEDED', 'CANCELLED'))
        """;
    String ELIGIBLE =
            COMPLETED_IMPORT
                    + """
         AND (
           item.clean_status = 'PENDING'
           <if test="includeReviews">
             OR (item.clean_status = 'REVIEW_REQUIRED' AND item.mapping_status = 'UNMAPPED')
           </if>
         )
         AND item.mapping_status != 'EXCLUDED'
        """;

    @Select(
            "<script> SELECT item.*, "
                    + SourceGoodsItemMapper.CREATED_DATE_COLUMN
                    + FILTER
                    + TAB
                    + " ORDER BY item.id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<SourceGoodsItem> results(
            String tenantKey,
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String keyword,
            String status,
            int limit,
            long offset);

    @Select(
            "<script> SELECT item.clean_status AS status, COUNT(*) AS total "
                    + FILTER
                    + " GROUP BY item.clean_status </script>")
    List<Count> counts(
            String tenantKey,
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String keyword);

    @Select("<script> SELECT COUNT(*) " + FILTER + ELIGIBLE + " </script>")
    long eligibleCount(
            String tenantKey,
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            boolean includeReviews,
            String keyword);

    @Select(SOURCE)
    SourceGoodsItem source(String tenantKey, String sourceKey, long id);

    @Select(SOURCE + " FOR UPDATE")
    SourceGoodsItem lockSource(String tenantKey, String sourceKey, long id);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND id = #{id}")
    CatalogCleaningJob job(String tenantKey, String sourceKey, long id);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND request_key = #{requestKey}")
    CatalogCleaningJob requested(String tenantKey, String sourceKey, String requestKey);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND active_slot = 1")
    CatalogCleaningJob active(String tenantKey, String sourceKey);

    @Select(
            "SELECT * FROM catalog_clean_job WHERE "
                    + SCOPE
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<CatalogCleaningJob> jobs(String tenantKey, String sourceKey, int limit, long offset);

    @Select("SELECT COUNT(*) FROM catalog_clean_job WHERE " + SCOPE)
    long countJobs(String tenantKey, String sourceKey);

    @Insert(
            """
        INSERT INTO catalog_clean_job
          (tenant_key, source_key, brand_name, created_date, include_reviews, target_source_id,
           request_key, requester_id, requester_name, status)
        VALUES (#{tenantKey}, #{sourceKey}, #{brandName}, #{createdDate}, #{includeReviews},
          #{targetSourceId}, #{requestKey}, #{requesterId}, #{requesterName}, 'RUNNING')
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertJob(CatalogCleaningJob job);

    @Insert(
            "<script> INSERT INTO catalog_clean_job_item (job_id, source_id, source_version) "
                    + "SELECT #{jobId}, item.id, item.record_version "
                    + FILTER
                    + ELIGIBLE
                    + " </script>")
    int insertMembers(
            long jobId,
            String tenantKey,
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            boolean includeReviews,
            String keyword);

    @Insert(
            """
        INSERT INTO catalog_clean_job_item (job_id, source_id, source_version)
        VALUES (#{jobId}, #{source.id}, #{source.recordVersion})
        """)
    int insertMember(long jobId, SourceGoodsItem source);

    @Select(
            "SELECT * FROM catalog_clean_job WHERE "
                    + SCOPE
                    + " AND active_slot = 1 AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)")
    CatalogCleaningJob due(String tenantKey, String sourceKey);

    @Update(
            """
        UPDATE catalog_clean_job SET worker_token = #{token},
          lease_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP)
        WHERE id = #{job.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
          AND status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
        """)
    int claim(CatalogCleaningJob job, String token);

    String LEASE =
            """
        id = #{job.id} AND tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
        AND status = 'RUNNING' AND worker_token = #{token} AND lease_until > CURRENT_TIMESTAMP
        """;

    @Select("SELECT id FROM catalog_clean_job WHERE " + LEASE + " FOR UPDATE")
    Long lockClaim(CatalogCleaningJob job, String token);

    @Update(
            """
        UPDATE catalog_clean_job SET worker_token = NULL, lease_until = NULL, status = #{status}
        WHERE
        """
                    + LEASE)
    int release(CatalogCleaningJob job, String token, String status);

    @Select(
            """
        SELECT source_id, source_version FROM catalog_clean_job_item
        WHERE job_id = #{id} AND outcome = 'PENDING' ORDER BY source_id LIMIT #{limit}
        """)
    List<Member> pending(long id, int limit);

    @Update(
            """
        UPDATE catalog_clean_job_item SET outcome = #{outcome}
        WHERE job_id = #{jobId} AND source_id = #{sourceId} AND outcome = 'PENDING'
        """)
    int finishMember(long jobId, long sourceId, String outcome);

    @Select(
            """
        <script>
        SELECT job_id, outcome AS status, COUNT(*) AS total FROM catalog_clean_job_item
        WHERE job_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        GROUP BY job_id, outcome
        </script>
        """)
    List<JobCount> jobCounts(List<Long> ids);

    @Update(
            """
            <script>
            UPDATE source_goods_item SET clean_status = #{change.status}, clean_job_id = #{job.id},
              clean_candidate = #{change.candidate},
              clean_error_code = #{change.code}, cleaned_at = CURRENT_TIMESTAMP,
              record_version = record_version + 1,
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
              AND brand_name = #{job.brandName}
              AND record_version = #{source.recordVersion} AND clean_status = #{source.cleanStatus}
            </script>
            """)
    int save(CatalogCleaningJob job, SourceGoodsItem source, Change change, UserInfo reviewer);

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
          (tenant_key, actor_type, operator_id, operator_name, entity_type, entity_id,
            action_type, batch_key, before_data, after_data, reason)
        VALUES (#{job.tenantKey},
          <choose>
            <when test="operator != null">'USER', #{operator.id}, #{operator.name},</when>
            <otherwise>'SYSTEM', NULL, NULL,</otherwise>
          </choose>
          #{entityType}, #{entityId}, #{action}, #{batch}, #{before}, #{after}, #{reason})
        </script>
        """)
    int audit(
            CatalogCleaningJob job,
            UserInfo operator,
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
          AND entity_type IN ('SOURCE_GOODS_ITEM', 'SYNC_JOB', 'CLEAN_JOB')
        """;
    String LOG_FIELDS =
            """
        SELECT id, entity_type, entity_id, actor_type, operator_name, action_type,
          reason, before_data, after_data, created_at
        """;
    String SOURCE_LOGS =
            """
        FROM catalog_operation_log WHERE tenant_key = #{tenant}
          AND entity_type = 'SOURCE_GOODS_ITEM' AND entity_id = #{sourceId} AND action_type <> 'CREATE'
        """;

    @Select(LOG_FIELDS + LOGS + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Audit> logs(String tenant, String batch, int limit, long offset);

    @Select("SELECT COUNT(*) " + LOGS)
    long countLogs(String tenant, String batch);

    @Select(LOG_FIELDS + SOURCE_LOGS + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<Audit> sourceLogs(String tenant, long sourceId, int limit, long offset);

    @Select("SELECT COUNT(*) " + SOURCE_LOGS)
    long countSourceLogs(String tenant, long sourceId);

    @Select("SELECT status FROM goods_sync_job WHERE id = #{id}")
    String importStatus(long id);

    record Member(long sourceId, int sourceVersion) {}

    record Count(String status, long total) {}

    record JobCount(long jobId, String status, long total) {}

    record Change(
            String status,
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
            String reason,
            String before,
            String after,
            LocalDateTime createdAt) {}
}
