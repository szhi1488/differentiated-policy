package com.company.policy.mapper;

import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SourceGoodsItemMapper {
    String ORIGINAL =
            "id, brand_name, goods_name, goods_no, sku_name, cate_name, goods_field4, "
                    + "goods_field1, goods_field5, sku_id, cate_full_name, gmt_modified, goods_gmt_modified,"
                    + " is_blockup, is_package_good, clean_status";
    String CREATED_DATE_LOG =
            """
        FROM catalog_operation_log marker
        WHERE marker.entity_type = 'SOURCE_GOODS_ITEM'
          AND marker.entity_id = item.id AND marker.actor_type = 'SYSTEM'
          AND marker.batch_key LIKE 'CREATED-DATE-%' AND marker.action_type = 'CREATE'
        """;
    String CREATED_DATE_JOB =
            """
        FROM goods_sync_job dated
        WHERE dated.source_key = item.source_key
          AND dated.brand_name = item.brand_name AND dated.sync_mode = 'CREATED'
          AND dated.id IN (item.first_seen_job_id, item.last_seen_job_id)
        """;
    String CREATED_DATE_COLUMN =
            """
        <choose>
          <when test="createdDate != null">CAST(#{createdDate} AS DATE)</when>
          <otherwise>
            COALESCE(
              (SELECT CAST(RIGHT(marker.batch_key, 10) AS DATE)
        """
                    + CREATED_DATE_LOG
                    + """
               ORDER BY marker.id DESC LIMIT 1),
              (SELECT dated.sync_date
        """
                    + CREATED_DATE_JOB
                    + """
               ORDER BY dated.id DESC LIMIT 1)
            )
          </otherwise>
        </choose>
        AS created_date
        """;
    String DATE_FILTER =
            """
        <if test="createdDate != null">
          AND (
            EXISTS (SELECT 1
        """
                    + CREATED_DATE_LOG
                    + """
                    AND marker.batch_key = CONCAT('CREATED-DATE-', #{createdDate}))
            OR EXISTS (SELECT 1
        """
                    + CREATED_DATE_JOB
                    + """
                       AND dated.sync_date = #{createdDate})
          )
        </if>
        """;
    String FILTER =
            """
        FROM source_goods_item item WHERE source_key = #{sourceKey}
        <if test="brand != null">AND brand_name = #{brand}</if>
        <if test="keyword != null">
          AND (LOCATE(#{keyword}, goods_name) > 0 OR LOCATE(#{keyword}, goods_no) > 0
               OR LOCATE(#{keyword}, sku_name) > 0 OR LOCATE(#{keyword}, cate_full_name) > 0
               OR sku_id = #{keyword})
        </if>
        """
                    + DATE_FILTER;

    @Select(
            """
        SELECT * FROM source_goods_item
        WHERE sku_id = #{skuId}
        FOR UPDATE
        """)
    SourceGoodsItem lockSku(String skuId);

    @Insert(
            """
        INSERT INTO source_goods_item
          (source_key, brand_name, goods_name, goods_no, sku_name, cate_name, goods_field4,
           goods_field1, goods_field5, sku_id, cate_full_name, gmt_modified, goods_gmt_modified,
           is_blockup, is_package_good, first_seen_job_id, last_seen_job_id)
        VALUES (#{job.sourceKey}, #{job.brandName}, #{goods.goodsName}, #{goods.goodsNo},
          #{goods.skuName}, #{goods.cateName}, #{goods.goodsField4}, #{goods.goodsField1}, #{goods.goodsField5},
          #{goods.skuId}, #{goods.cateFullName}, #{goods.gmtModified}, #{goods.goodsGmtModified},
          #{goods.isBlockup}, #{goods.isPackageGood}, #{job.id}, #{job.id})
        """)
    int insert(GoodsSyncJob job, Goods goods);

    @Update(
            """
        <script>
        UPDATE source_goods_item SET goods_name = #{goods.goodsName}, goods_no = #{goods.goodsNo},
          sku_name = #{goods.skuName}, cate_name = #{goods.cateName}, goods_field4 = #{goods.goodsField4},
          goods_field1 = #{goods.goodsField1}, goods_field5 = #{goods.goodsField5}, cate_full_name = #{goods.cateFullName},
          gmt_modified = #{goods.gmtModified}, goods_gmt_modified = #{goods.goodsGmtModified},
          is_blockup = #{goods.isBlockup}, is_package_good = #{goods.isPackageGood},
          <if test="recheck">
            clean_status = 'PENDING', clean_job_id = NULL,
            clean_candidate = NULL, clean_error_code = NULL, cleaned_at = NULL,
          </if>
          last_seen_job_id = #{job.id}, updated_at = CURRENT_TIMESTAMP, record_version = record_version + 1
        WHERE source_key = #{job.sourceKey}
          AND brand_name = #{job.brandName} AND id = #{item.id}
          AND record_version = #{item.recordVersion}
        </script>
        """)
    int updateSku(GoodsSyncJob job, SourceGoodsItem item, Goods goods, boolean recheck);

    @Update(
            """
        UPDATE source_goods_item SET last_seen_job_id = #{job.id}
        WHERE source_key = #{job.sourceKey}
          AND brand_name = #{job.brandName} AND id = #{id} AND last_seen_job_id <> #{job.id}
        """)
    int markSeen(GoodsSyncJob job, long id);

    @Insert(
            """
        <script>
        INSERT INTO catalog_operation_log
          (actor_type, entity_type, entity_id, action_type, batch_key, reason)
        SELECT DISTINCT 'SYSTEM', 'SOURCE_GOODS_ITEM',
               item.id, 'CREATE', CONCAT('CREATED-DATE-', dated.sync_date), '吉客云按建档日查询命中。'
        FROM source_goods_item item
        JOIN goods_sync_job dated
          ON dated.source_key = item.source_key
          AND dated.brand_name = item.brand_name AND dated.sync_mode = 'CREATED'
          AND dated.id IN (item.first_seen_job_id, item.last_seen_job_id)
        LEFT JOIN catalog_operation_log marker
          ON marker.entity_type = 'SOURCE_GOODS_ITEM'
          AND marker.entity_id = item.id AND marker.actor_type = 'SYSTEM'
          AND marker.action_type = 'CREATE'
          AND marker.batch_key = CONCAT('CREATED-DATE-', dated.sync_date)
        WHERE item.source_key = #{job.sourceKey}
          AND item.brand_name = #{job.brandName}
          AND marker.id IS NULL AND item.sku_id IN
          <foreach collection="skuIds" item="skuId" open="(" separator="," close=")">
            #{skuId}
          </foreach>
        </script>
        """)
    int recordCreatedDates(GoodsSyncJob job, List<String> skuIds);

    @Select("<script> SELECT COUNT(*) " + FILTER + "</script>")
    long count(String sourceKey, String brand, String keyword, LocalDate createdDate);

    @Select(
            "<script> SELECT "
                    + ORIGINAL
                    + ", "
                    + CREATED_DATE_COLUMN
                    + FILTER
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<SourceGoodsItem> page(
            String sourceKey,
            String brand,
            String keyword,
            LocalDate createdDate,
            int limit,
            long offset);
}
