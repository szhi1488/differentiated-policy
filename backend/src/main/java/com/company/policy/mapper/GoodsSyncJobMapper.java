package com.company.policy.mapper;

import com.company.policy.dto.JackyunGoodsQuery.Phase;
import com.company.policy.entity.GoodsSyncJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsSyncJobMapper {
    String FIELDS = "*, next_request_at <= CURRENT_TIMESTAMP AS ready";
    String SCOPE = "source_key = #{sourceKey}";
    String DUE =
            "status IN ('QUEUED', 'RUNNING') AND next_request_at <= CURRENT_TIMESTAMP "
                    + "AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)";
    String OWNED =
            "id = #{job.id} AND source_key = #{job.sourceKey} "
                    + "AND status = 'RUNNING' AND record_version = #{job.recordVersion} "
                    + "AND worker_token = #{token} AND lease_until > CURRENT_TIMESTAMP";

    @Select(
            "SELECT "
                    + FIELDS
                    + " FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND id = (SELECT MAX(id) FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND brand_name = #{brand})")
    GoodsSyncJob latest(String sourceKey, String brand);

    @Select("SELECT " + FIELDS + " FROM goods_sync_job WHERE " + SCOPE + " AND id = #{id}")
    GoodsSyncJob find(String sourceKey, long id);

    @Select(
            "SELECT "
                    + FIELDS
                    + " FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND idempotency_key = #{key}")
    GoodsSyncJob findByKey(String sourceKey, String key);

    @Select(
            "SELECT "
                    + FIELDS
                    + " FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND active_slot = 1 AND "
                    + DUE
                    + " AND NOT EXISTS (SELECT 1 FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND status = 'CANCELLED' AND error_code IS NOT NULL AND next_request_at > CURRENT_TIMESTAMP)")
    GoodsSyncJob findDue(String sourceKey);

    @Insert(
            """
        INSERT INTO goods_sync_job
          (source_key, requester_id, brand_name, idempotency_key,
           page_size, include_blockup, sync_mode, sync_date, sync_phase, next_request_at)
        VALUES (#{job.sourceKey}, #{job.requesterId}, #{job.brandName}, #{job.idempotencyKey},
           #{job.pageSize}, #{job.includeBlockup}, #{job.syncMode}, #{job.syncDate}, #{job.syncPhase},
           TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP))
        """)
    @Options(useGeneratedKeys = true, keyProperty = "job.id")
    int insert(@Param("job") GoodsSyncJob job, @Param("delaySeconds") int delaySeconds);

    @Select(
            "SELECT MAX(next_request_at) > CURRENT_TIMESTAMP FROM goods_sync_job WHERE "
                    + SCOPE
                    + " AND status = 'CANCELLED' AND error_code IS NOT NULL")
    Boolean coolingDown(String sourceKey);

    @Update(
            """
        UPDATE goods_sync_job SET status = 'RUNNING', worker_token = #{token},
          lease_until = TIMESTAMPADD(SECOND, 90, CURRENT_TIMESTAMP),
          started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
          fencing_token = fencing_token + 1, record_version = record_version + 1
        WHERE id = #{job.id} AND source_key = #{job.sourceKey}
          AND record_version = #{job.recordVersion} AND
        """
                    + DUE)
    int claim(GoodsSyncJob job, String token);

    @Select("SELECT id FROM goods_sync_job WHERE " + OWNED + " FOR UPDATE")
    Long lockOwned(GoodsSyncJob job, String token);

    @Update(
            """
        UPDATE goods_sync_job SET status = #{status}, last_max_id = #{cursor}, sync_phase = #{phase},
          read_count = read_count + #{rows}, content_count = content_count + #{contents},
          page_count = page_count + 1, worker_token = NULL, lease_until = NULL,
          next_request_at = TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP),
          finished_at = CASE WHEN #{status} = 'SUCCEEDED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          record_version = record_version + 1
        WHERE
        """
                    + OWNED)
    int advance(
            GoodsSyncJob job,
            String token,
            String status,
            String cursor,
            Phase phase,
            int rows,
            int contents,
            int delaySeconds);

    @Update(
            """
        UPDATE goods_sync_job SET status = 'PAUSED', error_code = #{code},
          failure_count = failure_count + 1, worker_token = NULL, lease_until = NULL,
          next_request_at = TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP),
          record_version = record_version + 1
        WHERE
        """
                    + OWNED)
    int pause(GoodsSyncJob job, String token, String code, int delaySeconds);

    @Update(
            """
        UPDATE goods_sync_job SET status = #{status},
          error_code = CASE WHEN #{status} = 'CANCELLED' THEN error_code ELSE NULL END,
          finished_at = CASE WHEN #{status} = 'CANCELLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
          record_version = record_version + 1
        WHERE id = #{job.id} AND source_key = #{job.sourceKey}
          AND record_version = #{job.recordVersion} AND status = 'PAUSED'
          AND (#{status} = 'CANCELLED' OR next_request_at <= CURRENT_TIMESTAMP)
        """)
    int control(GoodsSyncJob job, String status);
}
