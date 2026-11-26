package com.company.policy.mapper;

import com.company.policy.entity.UserInfo;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PolicyReviewSettingsMapper {
    @Select("SELECT version FROM policy_review_settings WHERE tenant_key=#{tenantKey}")
    Integer version(String tenantKey);

    @Insert(
            "INSERT INTO policy_review_settings(tenant_key,version,updated_at) VALUES(#{tenantKey},0,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE tenant_key=VALUES(tenant_key)")
    void ensureSettings(String tenantKey);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT version FROM policy_review_settings WHERE tenant_key=#{tenantKey} FOR UPDATE")
    int lockVersion(String tenantKey);

    @Update(
            "UPDATE policy_review_settings SET version=version+1,updated_by=#{adminId},updated_at=CURRENT_TIMESTAMP WHERE tenant_key=#{tenantKey} AND version=#{version}")
    int advance(String tenantKey, long adminId, int version);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select(
            """
        <script>SELECT id,tenant_key,open_id,name,department,is_deleted FROM user_info
        WHERE tenant_key=#{tenantKey} AND id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY id FOR UPDATE</script>
        """)
    List<UserInfo> lockUsers(String tenantKey, List<Long> ids);

    @Select(
            "<script>SELECT COUNT(*) FROM user_info u WHERE u.tenant_key=#{tenant} AND u.id=#{id} AND u.is_deleted=0 AND "
                    + ReviewPermissions.FOR_GROUP
                    + "</script>")
    boolean eligible(String tenant, long id, String group);

    record Brand(
            long id,
            String name,
            String productMode,
            Long purchaseId,
            String purchaseName,
            String purchaseDepartment,
            Long productId,
            String productName,
            String productDepartment) {}

    record Series(
            long id,
            long brandId,
            String name,
            Long productId,
            String productName,
            String productDepartment) {}

    @Select(
            """
      SELECT b.id,b.name,o.product_mode,o.purchase_user_id AS purchase_id,p.name AS purchase_name,p.department AS purchase_department,
        o.brand_product_user_id AS product_id,u.name AS product_name,u.department AS product_department FROM brand b
      LEFT JOIN policy_review_brand_owner o ON o.brand_id=b.id AND o.tenant_key=#{tenant}
      LEFT JOIN user_info p ON p.id=o.purchase_user_id AND p.tenant_key=o.tenant_key
      LEFT JOIN user_info u ON u.id=o.brand_product_user_id AND u.tenant_key=o.tenant_key ORDER BY b.id
      """)
    List<Brand> brands(String tenant);

    @Select(
            """
      SELECT s.id,s.brand_id,s.name,o.product_user_id AS product_id,u.name AS product_name,u.department AS product_department
      FROM series s LEFT JOIN policy_review_series_owner o ON o.series_id=s.id AND o.brand_id=s.brand_id AND o.tenant_key=#{tenant}
      LEFT JOIN user_info u ON u.id=o.product_user_id AND u.tenant_key=o.tenant_key ORDER BY s.brand_id,s.id
      """)
    List<Series> series(String tenant);

    record Owners(
            String brandName,
            long purchaseId,
            String productMode,
            Long brandProductId,
            Long seriesProductId) {}

    @Select(
            """
      SELECT brand.name AS brand_name,b.purchase_user_id AS purchase_id,b.product_mode,
        b.brand_product_user_id AS brand_product_id,s.product_user_id AS series_product_id
      FROM policy_review_brand_owner b JOIN brand ON brand.id=b.brand_id
      JOIN series c ON c.brand_id=b.brand_id AND c.id=#{seriesId}
      LEFT JOIN policy_review_series_owner s ON s.series_id=c.id AND s.brand_id=b.brand_id AND s.tenant_key=b.tenant_key
      WHERE b.tenant_key=#{tenant} AND b.brand_id=#{brandId}
      """)
    Owners owners(String tenant, long brandId, long seriesId);

    @Delete("DELETE FROM policy_review_series_owner WHERE tenant_key=#{tenant}")
    void clearSeries(String tenant);

    @Delete("DELETE FROM policy_review_brand_owner WHERE tenant_key=#{tenant}")
    void clearBrands(String tenant);

    @Insert(
            "INSERT INTO policy_review_brand_owner(tenant_key,brand_id,purchase_user_id,product_mode,brand_product_user_id) VALUES(#{tenant},#{brandId},#{purchase},#{mode},#{product})")
    void insertBrand(String tenant, long brandId, long purchase, String mode, Long product);

    @Insert(
            "INSERT INTO policy_review_series_owner(tenant_key,brand_id,series_id,product_user_id) VALUES(#{tenant},#{brandId},#{seriesId},#{product})")
    void insertSeries(String tenant, long brandId, long seriesId, long product);
}
