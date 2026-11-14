package com.company.policy.mapper;

import com.company.policy.dto.CatalogDictionaryRequest.Type;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

@Mapper
public interface CatalogDictionaryMapper {
    String MODEL_IN_USE =
            """
        (EXISTS (SELECT 1 FROM goods g WHERE g.spu_id = spu.id)
         OR EXISTS (SELECT 1 FROM policy_detail d WHERE d.spu_id = spu.id))
        """;

    @Select(
            """
        SELECT 'brand' AS type, b.id, CAST(NULL AS SIGNED) AS parent_id, b.name,
          (EXISTS (SELECT 1 FROM series s WHERE s.brand_id = b.id)
           OR EXISTS (SELECT 1 FROM source_goods_item src WHERE src.catalog_brand_id = b.id)) AS in_use
        FROM brand b
        UNION ALL
        SELECT 'series', s.id, s.brand_id, s.name,
          EXISTS (SELECT 1 FROM spu WHERE spu.series_id = s.id AND
        """
                    + MODEL_IN_USE
                    + """
          )
        FROM series s
        UNION ALL
        SELECT 'color', c.id, CAST(NULL AS SIGNED), c.color_name,
          EXISTS (SELECT 1 FROM goods g WHERE g.color_id = c.id)
        FROM color_dict c
        ORDER BY type, id
        """)
    List<Entry> entries();

    @SelectProvider(type = Statements.class, method = "lockName")
    String lockName(Type type, long id);

    @Select("SELECT id FROM series WHERE brand_id = #{id} LIMIT 1 FOR UPDATE")
    Long linkedSeries(long id);

    @Select("SELECT id FROM source_goods_item WHERE catalog_brand_id = #{id} LIMIT 1 FOR UPDATE")
    Long linkedSource(long id);

    @Select("SELECT id FROM spu WHERE series_id = #{id} ORDER BY id FOR UPDATE")
    List<Long> lockModels(long id);

    @Select("SELECT EXISTS (SELECT 1 FROM spu WHERE series_id = #{id} AND " + MODEL_IN_USE + ")")
    boolean hasReferencedModel(long id);

    @Delete("DELETE FROM spu WHERE series_id = #{id} AND NOT " + MODEL_IN_USE)
    int deleteUnusedModels(long id);

    @Select("SELECT id FROM goods WHERE color_id = #{id} LIMIT 1 FOR UPDATE")
    Long linkedGoods(long id);

    @UpdateProvider(type = Statements.class, method = "rename")
    int rename(Type type, long id, String name);

    @DeleteProvider(type = Statements.class, method = "delete")
    int delete(Type type, long id);

    record Entry(Type type, long id, Long parentId, String name, boolean inUse) {}

    // 表和列由服务端枚举决定，名称及 ID 通过 MyBatis 参数绑定。
    class Statements {
        public static String lockName(@Param("type") Type type) {
            return "SELECT "
                    + column(type)
                    + " FROM "
                    + table(type)
                    + " WHERE id = #{id} FOR UPDATE";
        }

        public static String rename(@Param("type") Type type) {
            return "UPDATE " + table(type) + " SET " + column(type) + " = #{name} WHERE id = #{id}";
        }

        public static String delete(@Param("type") Type type) {
            return "DELETE FROM " + table(type) + " WHERE id = #{id}";
        }

        private static String table(Type type) {
            return switch (type) {
                case brand -> "brand";
                case series -> "series";
                case color -> "color_dict";
            };
        }

        private static String column(Type type) {
            return type == Type.color ? "color_name" : "name";
        }
    }
}
