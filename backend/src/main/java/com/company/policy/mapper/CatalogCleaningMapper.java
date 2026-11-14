package com.company.policy.mapper;

import com.company.policy.entity.CatalogCleaningJob;
import com.company.policy.entity.CatalogCleaningJobItem;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.entity.UserInfo;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogCleaningMapper {
    String SCOPE = "source_key = #{sourceKey}";
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
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            String keyword);

    @Select("<script> SELECT COUNT(*) " + FILTER + ELIGIBLE + " </script>")
    long eligibleCount(
            String sourceKey,
            String brand,
            LocalDate createdDate,
            boolean unknownDate,
            boolean includeReviews,
            String keyword);

    @Select(
            "<script> SELECT item.*, "
                    + SourceGoodsItemMapper.CREATED_DATE_COLUMN
                    + " FROM source_goods_item item WHERE "
                    + SCOPE
                    + " AND id = #{id} </script>")
    SourceGoodsItem source(String sourceKey, long id, LocalDate createdDate);

    @Select(SOURCE + " FOR UPDATE")
    SourceGoodsItem lockSource(String sourceKey, long id);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND id = #{id}")
    CatalogCleaningJob job(String sourceKey, long id);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND request_key = #{requestKey}")
    CatalogCleaningJob requested(String sourceKey, String requestKey);

    @Select("SELECT * FROM catalog_clean_job WHERE " + SCOPE + " AND active_slot = 1")
    CatalogCleaningJob active(String sourceKey);

    @Select(
            "SELECT * FROM catalog_clean_job WHERE "
                    + SCOPE
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}")
    List<CatalogCleaningJob> jobs(String sourceKey, int limit, long offset);

    @Select("SELECT COUNT(*) FROM catalog_clean_job WHERE " + SCOPE)
    long countJobs(String sourceKey);

    @Insert(
            """
        INSERT INTO catalog_clean_job
          (source_key, brand_name, created_date, include_reviews, target_source_id,
           request_key, requester_id, requester_name, status)
        VALUES (#{sourceKey}, #{brandName}, #{createdDate}, #{includeReviews},
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
    CatalogCleaningJob due(String sourceKey);

    @Update(
            """
        UPDATE catalog_clean_job SET worker_token = #{token},
          lease_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP)
        WHERE id = #{job.id} AND source_key = #{job.sourceKey}
          AND status = 'RUNNING' AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
        """)
    int claim(CatalogCleaningJob job, String token);

    String LEASE =
            """
        id = #{job.id} AND source_key = #{job.sourceKey}
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
        SELECT job_id, source_id, source_version, outcome FROM catalog_clean_job_item
        WHERE job_id = #{id} AND outcome = 'PENDING' ORDER BY source_id LIMIT #{limit}
        """)
    List<CatalogCleaningJobItem> pending(long id, int limit);

    @Update(
            """
        UPDATE catalog_clean_job_item SET outcome = #{outcome}
        WHERE job_id = #{member.jobId} AND source_id = #{member.sourceId} AND outcome = 'PENDING'
        """)
    int finishMember(CatalogCleaningJobItem member, String outcome);

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
              clean_error_code = #{change.code}, cleaned_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
              record_version = record_version + 1,
              <choose>
                <when test="reviewer != null">
                  reviewed_by = #{reviewer.id}, reviewed_at = CURRENT_TIMESTAMP, review_reason = #{change.reason},
                </when>
                <otherwise>
                  clean_attempts = clean_attempts + 1,
                  <if test="change.status == 'AUTO_PASSED'">
                    reviewed_by = NULL, reviewed_at = NULL, review_reason = NULL,
                  </if>
                </otherwise>
              </choose>
              <if test="change.mappingStatus != null">
                mapping_status = #{change.mappingStatus}, mapping_origin = #{change.origin},
                catalog_brand_id = #{change.brandId}, goods_id = #{change.goodsId},
              </if>
              mapping_remark = #{change.reason}
            WHERE id = #{source.id} AND source_key = #{job.sourceKey}
              AND brand_name = #{job.brandName}
              AND record_version = #{source.recordVersion} AND clean_status = #{source.cleanStatus}
            </script>
            """)
    int save(CatalogCleaningJob job, SourceGoodsItem source, Change change, UserInfo reviewer);

    @Select("SELECT status FROM goods_sync_job WHERE id = #{id}")
    String importStatus(long id);

    record Count(String status, long total) {}

    record JobCount(long jobId, String status, long total) {}

    record Change(
            String status,
            String candidate,
            String code,
            String reason,
            String mappingStatus,
            String origin,
            Long brandId,
            Long goodsId) {}
}
