package com.company.policy.mapper;

import com.company.policy.entity.GoodsSyncJob;
import com.company.policy.entity.SourceGoodsItem;
import com.company.policy.vo.JackyunGoodsPage.Goods;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SourceGoodsItemMapper {
    String ORIGINAL =
            "id, brand_name, goods_name, goods_no, sku_name, cate_name, goods_field4, "
                    + "goods_field1, goods_field5, clean_status";
    String FILTER =
            """
        FROM source_goods_item WHERE tenant_key = #{tenantKey} AND source_key = #{sourceKey}
        <if test="brand != null">AND brand_name = #{brand}</if>
        <if test="keyword != null">
          AND (LOCATE(#{keyword}, goods_name) > 0 OR LOCATE(#{keyword}, goods_no) > 0
               OR LOCATE(#{keyword}, sku_name) > 0)
        </if>
        """;

    @Select(
            "SELECT "
                    + ORIGINAL
                    + """
        , collision_no, last_seen_job_id FROM source_goods_item
        WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
          AND brand_name = #{job.brandName} AND content_hash = #{hash} ORDER BY collision_no
        """)
    List<SourceGoodsItem> matchingHash(GoodsSyncJob job, String hash);

    @Insert(
            """
        INSERT INTO source_goods_item
          (tenant_key, source_key, brand_name, goods_name, goods_no, sku_name, cate_name, goods_field4,
           goods_field1, goods_field5, content_hash, collision_no, first_seen_job_id, last_seen_job_id)
        VALUES (#{job.tenantKey}, #{job.sourceKey}, #{job.brandName}, #{goods.goodsName}, #{goods.goodsNo},
          #{goods.skuName}, #{goods.cateName}, #{goods.goodsField4}, #{goods.goodsField1}, #{goods.goodsField5},
          #{hash}, #{collision}, #{job.id}, #{job.id})
        """)
    int insert(GoodsSyncJob job, Goods goods, String hash, int collision);

    @Update(
            """
        UPDATE source_goods_item SET last_seen_job_id = #{job.id}, record_version = record_version + 1
        WHERE tenant_key = #{job.tenantKey} AND source_key = #{job.sourceKey}
          AND brand_name = #{job.brandName} AND id = #{id} AND last_seen_job_id <> #{job.id}
        """)
    int markSeen(GoodsSyncJob job, long id);

    @Select("<script> SELECT COUNT(*) " + FILTER + "</script>")
    long count(String tenantKey, String sourceKey, String brand, String keyword);

    @Select(
            "<script> SELECT "
                    + ORIGINAL
                    + " "
                    + FILTER
                    + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset} </script>")
    List<SourceGoodsItem> page(
            String tenantKey,
            String sourceKey,
            String brand,
            String keyword,
            int limit,
            long offset);
}
