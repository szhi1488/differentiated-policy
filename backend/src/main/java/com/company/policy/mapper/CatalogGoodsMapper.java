package com.company.policy.mapper;

import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.entity.CatalogGoods;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogGoodsMapper {
    @Select("SELECT id FROM brand WHERE name = #{name} FOR UPDATE")
    Long lockBrand(String name);

    @Insert("INSERT INTO brand (name) VALUES (#{name})")
    int insertBrand(String name);

    @Select("SELECT id FROM series WHERE brand_id = #{brand} AND name = #{name} FOR UPDATE")
    Long lockSeries(long brand, String name);

    @Insert("INSERT INTO series (brand_id, name) VALUES (#{brand}, #{name})")
    int insertSeries(long brand, String name);

    @Select("SELECT id FROM series WHERE id = #{series} AND brand_id = #{brand} FOR UPDATE")
    Long lockSeriesBinding(long brand, long series);

    @Select(
            """
        SELECT p.id, p.series_id, s.name AS series_name FROM spu p
        JOIN series s ON s.id = p.series_id AND s.brand_id = p.brand_id
        WHERE p.brand_id = #{brand} AND p.name = #{name} AND p.edition = #{edition}
        FOR UPDATE
        """)
    Model lockModel(long brand, String name, String edition);

    @Insert(
            """
        INSERT INTO spu (brand_id, series_id, name, edition)
        VALUES (#{brand}, #{series}, #{name}, #{edition})
        """)
    int insertModel(long brand, long series, String name, String edition);

    @Select("SELECT id FROM color_dict WHERE color_name = #{name} FOR UPDATE")
    Long lockColor(String name);

    @Insert("INSERT INTO color_dict (color_name) VALUES (#{name})")
    int insertColor(String name);

    @Select(
            """
        SELECT id, catalog_status, status FROM goods
        WHERE spu_id = #{spu} AND ram = #{ram} AND rom = #{rom} AND color_id = #{color}
        FOR UPDATE
        """)
    Match lockGoods(long spu, String ram, String rom, long color);

    @Insert(
            """
        INSERT INTO goods (sku_id, product_no, brand_id, series_id, spu_id, goods_name,
                           ram, rom, color_id, status, catalog_status)
        VALUES (#{sku}, #{productNo}, #{brand}, #{series}, #{spu}, #{name},
                #{ram}, #{rom}, #{color}, #{status}, 'ACTIVE')
        """)
    int insertGoods(
            String sku,
            String productNo,
            long brand,
            long series,
            long spu,
            String name,
            String ram,
            String rom,
            long color,
            int status);

    @Update(
            "UPDATE goods SET status = 1 WHERE id = #{id} AND catalog_status = 'ACTIVE' AND status IS NULL")
    int defaultToOnSale(long id);

    @Update(
            """
        UPDATE goods SET catalog_status = 'DISABLED'
        WHERE id = #{id} AND catalog_status IN ('ACTIVE', 'DRAFT')
        """)
    int disable(long id);

    @Select("SELECT catalog_status FROM goods WHERE id = #{id} FOR UPDATE")
    String lockCatalogStatus(long id);

    String COLUMNS =
            """
        SELECT g.id, g.sku_id, g.product_no, g.goods_name, g.brand_id, b.name AS brand_name,
          g.series_id, s.name AS series_name, g.spu_id, p.name AS model_name, p.edition,
          g.ram, g.rom, g.color_id, c.color_name, g.status, g.catalog_status, g.updated_at,
          source.sku_id AS source_sku_id, source.goods_name AS source_name
        """;
    String JOINED =
            """
        FROM goods g JOIN brand b ON b.id = g.brand_id
        JOIN series s ON s.id = g.series_id
        JOIN spu p ON p.id = g.spu_id
        JOIN color_dict c ON c.id = g.color_id
        """;
    String REPRESENTATIVE_SOURCE =
            """
        LEFT JOIN source_goods_item source ON source.id = (
          SELECT item.id FROM source_goods_item item
          WHERE item.goods_id = g.id AND item.catalog_brand_id = g.brand_id
          ORDER BY item.id LIMIT 1
        )
        """;
    String FILTER =
            """
        <where>
          <if test="query.brandName != null">AND b.name = #{query.brandName}</if>
          <if test="query.seriesName != null">AND LOCATE(#{query.seriesName}, s.name) > 0</if>
          <if test="query.seriesId != null">AND g.series_id = #{query.seriesId}</if>
          <if test="query.spuId != null">AND g.spu_id = #{query.spuId}</if>
          <if test="query.ram != null">AND g.ram = #{query.ram} AND g.rom = #{query.rom}</if>
          <if test="query.modelName != null">AND LOCATE(#{query.modelName}, p.name) > 0</if>
          <choose>
            <when test="query.catalogStatus != null">AND g.catalog_status = #{query.catalogStatus}</when>
            <otherwise>AND g.catalog_status IN ('ACTIVE', 'DRAFT')</otherwise>
          </choose>
          <if test="query.status != null">AND g.status = #{query.status}</if>
          <if test="query.keyword != null">
            AND (LOCATE(#{query.keyword}, g.goods_name) > 0
                 OR LOCATE(#{query.keyword}, g.product_no) > 0
                 OR LOCATE(#{query.keyword}, g.sku_id) > 0
                 OR LOCATE(#{query.keyword}, c.color_name) > 0
                 OR EXISTS (SELECT 1 FROM source_goods_item item
                   WHERE item.goods_id = g.id AND item.catalog_brand_id = g.brand_id
                     AND LOCATE(#{query.keyword}, item.sku_id) > 0))
          </if>
        </where>
        """;

    @Select(
            "<script>"
                    + COLUMNS
                    + JOINED
                    + REPRESENTATIVE_SOURCE
                    + FILTER
                    + """
        <choose>
          <when test="query.ascending">ORDER BY g.updated_at ASC, g.id ASC</when>
          <otherwise>ORDER BY g.updated_at DESC, g.id DESC</otherwise>
        </choose>
        LIMIT #{size} OFFSET #{offset}
        </script>
        """)
    List<CatalogGoods> page(CatalogGoodsQuery query, int size, long offset);

    @Select("<script>SELECT COUNT(*) " + JOINED + FILTER + "</script>")
    long count(@Param("query") CatalogGoodsQuery query);

    @Select(COLUMNS + JOINED + REPRESENTATIVE_SOURCE + " WHERE g.id = #{id}")
    CatalogGoods find(long id);

    @Select(
            """
        SELECT b.name AS brand_name, s.id AS series_id, s.name AS series_name,
               COUNT(*) AS total,
               COUNT(CASE WHEN g.status = 1 THEN 1 END) AS active,
               COUNT(CASE WHEN g.status = 4 THEN 1 END) AS stopped
        FROM goods g JOIN brand b ON b.id = g.brand_id JOIN series s ON s.id = g.series_id
        WHERE g.catalog_status IN ('ACTIVE', 'DRAFT')
        GROUP BY b.id, b.name, s.id, s.name ORDER BY b.id, s.id
        """)
    List<SeriesCount> seriesCounts();

    @Select(
            """
        SELECT g.series_id, p.id AS spu_id, p.name AS model_name, p.edition,
               COUNT(*) AS total, COUNT(CASE WHEN g.status = 1 THEN 1 END) AS active
        FROM goods g JOIN spu p ON p.id = g.spu_id
        WHERE g.catalog_status IN ('ACTIVE', 'DRAFT')
        GROUP BY g.series_id, p.id, p.name, p.edition ORDER BY g.series_id, p.name, p.edition, p.id
        """)
    List<ModelCount> modelCounts();

    @Select(
            "SELECT DISTINCT ram, rom FROM goods WHERE catalog_status IN ('ACTIVE', 'DRAFT') ORDER BY ram, rom")
    List<Memory> configurations();

    record SeriesCount(
            String brandName,
            long seriesId,
            String seriesName,
            long total,
            long active,
            long stopped) {}

    record Memory(String ram, String rom) {}

    record ModelCount(
            long seriesId, long spuId, String modelName, String edition, long total, long active) {}

    record Model(long id, long seriesId, String seriesName) {}

    record Match(long id, String catalogStatus, Integer status) {}
}
