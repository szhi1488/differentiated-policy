package com.company.policy.mapper;

import com.company.policy.dto.CatalogGoodsQuery;
import com.company.policy.entity.Brand;
import com.company.policy.entity.CatalogGoods;
import com.company.policy.entity.ColorDict;
import com.company.policy.entity.Series;
import com.company.policy.entity.Spu;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogGoodsMapper {
    @Select("SELECT id, name, created_at FROM brand WHERE name = #{name} FOR UPDATE")
    Brand lockBrand(String name);

    @Insert("INSERT INTO brand (name) VALUES (#{name})")
    int insertBrand(String name);

    @Select(
            "SELECT id, brand_id, name, created_at FROM series WHERE brand_id = #{brand} AND name = #{name} FOR UPDATE")
    Series lockSeries(long brand, String name);

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
        VALUES (#{brandId}, #{seriesId}, #{name}, #{edition})
        """)
    int insertModel(Spu model);

    @Select("SELECT id, color_name FROM color_dict WHERE color_name = #{name} FOR UPDATE")
    ColorDict lockColor(String name);

    @Insert("INSERT INTO color_dict (color_name) VALUES (#{name})")
    int insertColor(String name);

    @Select(
            """
        SELECT id, status FROM goods
        WHERE spu_id = #{spu} AND ram = #{ram} AND rom = #{rom} AND color_id = #{color}
        FOR UPDATE
        """)
    Match lockGoods(long spu, String ram, String rom, long color);

    @Insert(
            """
        INSERT INTO goods (sku_id, product_no, brand_id, series_id, spu_id, goods_name,
                           ram, rom, color_id, status)
        VALUES (#{sku}, #{productNo}, #{brand}, #{series}, #{spu}, #{name},
                #{ram}, #{rom}, #{color}, #{status})
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

    @Update("UPDATE goods SET status = 1 WHERE id = #{id} AND status IS NULL")
    int defaultToOnSale(long id);

    @Delete("DELETE FROM goods WHERE id = #{id}")
    int delete(long id);

    @Select("SELECT id FROM goods WHERE id = #{id} FOR UPDATE")
    Long lockById(long id);

    @Select("SELECT EXISTS(SELECT 1 FROM source_goods_item WHERE goods_id = #{id})")
    boolean hasSourceMapping(long id);

    @Select("SELECT EXISTS(SELECT 1 FROM policy_goods_detail WHERE goods_id = #{id})")
    boolean hasPolicyGoodsReference(long id);

    @Update(
            """
        UPDATE goods SET goods_name = #{name}, brand_id = #{brand}, series_id = #{series},
                         spu_id = #{spu}, ram = #{ram}, rom = #{rom}, color_id = #{color},
                         status = #{status,jdbcType=TINYINT}
        WHERE id = #{id}
        """)
    int updateGoods(
            long id,
            String name,
            long brand,
            long series,
            long spu,
            String ram,
            String rom,
            long color,
            Integer status);

    String PRODUCT_COLUMNS =
            """
        SELECT g.id, g.sku_id, g.product_no, g.goods_name, g.brand_id, b.name AS brand_name,
          g.series_id, s.name AS series_name, g.spu_id, p.name AS model_name, p.edition,
          g.ram, g.rom, g.color_id, c.color_name, g.status, g.updated_at
        """;
    String COLUMNS =
            PRODUCT_COLUMNS + ", source.sku_id AS source_sku_id, source.goods_name AS source_name ";
    String APPLICANT_COLUMNS = PRODUCT_COLUMNS + ", NULL AS source_sku_id, NULL AS source_name ";
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
          <if test="query.brandId != null">AND g.brand_id = #{query.brandId}</if>
          <if test="query.brandName != null">AND b.name = #{query.brandName}</if>
          <if test="query.seriesName != null">AND LOCATE(#{query.seriesName}, s.name) > 0</if>
          <if test="query.seriesId != null">AND g.series_id = #{query.seriesId}</if>
          <if test="query.spuId != null">AND g.spu_id = #{query.spuId}</if>
          <if test="query.ram != null">AND g.ram = #{query.ram} AND g.rom = #{query.rom}</if>
          <if test="!query.configurations.isEmpty()">
            AND
            <foreach collection="query.configurations" item="config" open="(" separator=" OR " close=")">
              (g.spu_id = #{config.spuId} AND g.ram = #{config.ram} AND g.rom = #{config.rom})
            </foreach>
          </if>
          <if test="query.modelName != null">AND LOCATE(#{query.modelName}, p.name) > 0</if>
          <if test="query.status != null">AND g.status = #{query.status}</if>
          <foreach collection="keywords" item="term">
            AND (LOCATE(#{term}, REPLACE(LOWER(g.goods_name), ' ', '')) > 0
                 OR LOCATE(#{term}, REPLACE(LOWER(p.name), ' ', '')) > 0
                 OR LOCATE(#{term}, LOWER(b.name)) > 0
                 OR LOCATE(#{term}, LOWER(s.name)) > 0
                 OR LOCATE(#{term}, LOWER(p.edition)) > 0
                 OR LOCATE(#{term}, LOWER(c.color_name)) > 0
                 <if test="!applicant">
                 OR LOCATE(#{term}, LOWER(g.product_no)) > 0
                 OR LOCATE(#{term}, LOWER(g.sku_id)) > 0
                 <if test="term.matches('[0-9]{1,19}')">
                 OR EXISTS (SELECT 1 FROM source_goods_item item
                   WHERE item.goods_id = g.id AND item.catalog_brand_id = g.brand_id
                     AND LOCATE(#{term}, item.sku_id) > 0)
                 </if>
                 </if>
            )
          </foreach>
        </where>
        """;

    @Select(
            "<script>"
                    + "<choose><when test='applicant'>"
                    + APPLICANT_COLUMNS
                    + "</when><otherwise>"
                    + COLUMNS
                    + "</otherwise></choose>"
                    + JOINED
                    + "<if test='!applicant'>"
                    + REPRESENTATIVE_SOURCE
                    + "</if>"
                    + FILTER
                    + """
        <choose>
          <when test="applicant">ORDER BY g.id ASC</when>
          <when test="query.ascending">ORDER BY g.updated_at ASC, g.id ASC</when>
          <otherwise>ORDER BY g.updated_at DESC, g.id DESC</otherwise>
        </choose>
        LIMIT #{size} OFFSET #{offset}
        </script>
        """)
    List<CatalogGoods> page(
            @Param("query") CatalogGoodsQuery query,
            @Param("keywords") List<String> keywords,
            @Param("size") int size,
            @Param("offset") long offset,
            @Param("applicant") boolean applicant);

    @Select("<script>SELECT COUNT(*) " + JOINED + FILTER + "</script>")
    long count(
            @Param("query") CatalogGoodsQuery query,
            @Param("keywords") List<String> keywords,
            @Param("applicant") boolean applicant);

    @Select(
            "<script>"
                    + APPLICANT_COLUMNS
                    + JOINED
                    + """
        WHERE g.id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<CatalogGoods> findForPolicy(@Param("ids") List<Long> ids);

    @Select(
            """
        <script>
        SELECT id FROM goods WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE
        </script>
        """)
    List<Long> lockSelected(@Param("ids") List<Long> ids);

    @Select(
            """
        SELECT DISTINCT b.id AS brand_id, b.name AS brand_name, s.id AS series_id,
            s.name AS series_name, p.id AS spu_id, p.name AS model_name, p.edition, g.ram, g.rom
        """
                    + JOINED
                    + """
        WHERE g.status = 1
        ORDER BY brand_id, series_id, spu_id, g.ram, g.rom
        """)
    List<OnSaleConfiguration> onSaleConfigurations();

    record OnSaleConfiguration(
            long brandId,
            String brandName,
            long seriesId,
            String seriesName,
            long spuId,
            String modelName,
            String edition,
            String ram,
            String rom) {}

    @Select(COLUMNS + JOINED + REPRESENTATIVE_SOURCE + " WHERE g.id = #{id}")
    CatalogGoods find(long id);

    @Select(
            """
        SELECT b.name AS brand_name, s.id AS series_id, s.name AS series_name,
               COUNT(*) AS total,
               COUNT(CASE WHEN g.status = 1 THEN 1 END) AS active,
               COUNT(CASE WHEN g.status = 4 THEN 1 END) AS stopped
        FROM goods g JOIN brand b ON b.id = g.brand_id JOIN series s ON s.id = g.series_id
        GROUP BY b.id, b.name, s.id, s.name ORDER BY b.id, s.id
        """)
    List<SeriesCount> seriesCounts();

    @Select(
            """
        SELECT g.series_id, p.id AS spu_id, p.name AS model_name, p.edition,
               COUNT(*) AS total, COUNT(CASE WHEN g.status = 1 THEN 1 END) AS active
        FROM goods g JOIN spu p ON p.id = g.spu_id
        GROUP BY g.series_id, p.id, p.name, p.edition ORDER BY g.series_id, p.name, p.edition, p.id
        """)
    List<ModelCount> modelCounts();

    @Select("SELECT DISTINCT ram, rom FROM goods ORDER BY ram, rom")
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

    record Match(long id, Integer status) {}
}
