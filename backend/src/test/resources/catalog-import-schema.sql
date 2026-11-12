-- 只在隔离的 H2 测试库使用；生产库由 docs/sql/schema.sql 维护。
CREATE TABLE goods_sync_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, source_key VARCHAR(64) NOT NULL,
    requester_id BIGINT NOT NULL, brand_name VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    sync_mode VARCHAR(16) NOT NULL DEFAULT 'FULL', sync_date DATE,
    sync_phase VARCHAR(16) NOT NULL DEFAULT 'FULL',
    last_max_id VARCHAR(32) NOT NULL DEFAULT '0', page_size INT NOT NULL, include_blockup TINYINT NOT NULL,
    page_count INT NOT NULL DEFAULT 0, read_count BIGINT NOT NULL DEFAULT 0, content_count BIGINT NOT NULL DEFAULT 0,
    clean_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED', clean_rule_version VARCHAR(64),
    failure_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64), worker_token VARCHAR(64), lease_until DATETIME, next_request_at DATETIME NOT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0, record_version INT NOT NULL DEFAULT 0,
    started_at DATETIME, finished_at DATETIME, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED', 'RUNNING', 'PAUSED') THEN 1 ELSE NULL END),
    UNIQUE (tenant_key, source_key, idempotency_key), UNIQUE (tenant_key, source_key, active_slot),
    UNIQUE (id, tenant_key, source_key, brand_name)
);

CREATE TABLE source_goods_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, source_key VARCHAR(64) NOT NULL, brand_name VARCHAR(32) NOT NULL,
    goods_name VARCHAR(512), goods_no VARCHAR(128), sku_name VARCHAR(256), cate_name VARCHAR(256),
    goods_field4 VARCHAR(1024), goods_field1 VARCHAR(1024), goods_field5 VARCHAR(1024),
    sku_id VARCHAR(19) NOT NULL, cate_full_name VARCHAR(1024),
    gmt_modified BIGINT, goods_gmt_modified BIGINT, is_blockup TINYINT, is_package_good TINYINT,
    first_seen_job_id BIGINT NOT NULL,
    last_seen_job_id BIGINT NOT NULL, record_version INT NOT NULL DEFAULT 0,
    clean_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    clean_job_id BIGINT, clean_rule_version VARCHAR(64), clean_candidate VARCHAR,
    clean_error_code VARCHAR(64), cleaned_at DATETIME, clean_run_key VARCHAR(64),
    clean_lease_until DATETIME, clean_attempts INT NOT NULL DEFAULT 0,
    mapping_status VARCHAR(16) NOT NULL DEFAULT 'UNMAPPED', mapping_origin VARCHAR(16),
    catalog_variant_id BIGINT, variant_color_id BIGINT, mapping_remark VARCHAR(512),
    reviewed_by BIGINT, reviewed_at DATETIME, review_reason VARCHAR(512),
    UNIQUE (tenant_key, source_key, sku_id),
    FOREIGN KEY (first_seen_job_id, tenant_key, source_key, brand_name) REFERENCES goods_sync_job (id, tenant_key, source_key, brand_name),
    FOREIGN KEY (last_seen_job_id, tenant_key, source_key, brand_name) REFERENCES goods_sync_job (id, tenant_key, source_key, brand_name)
);

CREATE TABLE catalog_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, operator_id BIGINT, operator_name VARCHAR(64),
    actor_type VARCHAR(16) NOT NULL DEFAULT 'USER', rule_version VARCHAR(64),
    entity_type VARCHAR(32) NOT NULL, entity_id BIGINT NOT NULL, action_type VARCHAR(32) NOT NULL,
    batch_key VARCHAR(64), before_data VARCHAR, after_data VARCHAR, reason VARCHAR(512),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((actor_type = 'USER' AND operator_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND operator_id IS NULL AND rule_version IS NOT NULL))
);

CREATE TABLE catalog_variant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, variant_code VARCHAR(64) NOT NULL,
    brand_name VARCHAR(32) NOT NULL, series_name VARCHAR(64) NOT NULL, model_name VARCHAR(64) NOT NULL,
    spec VARCHAR(64) NOT NULL, display_name VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL,
    UNIQUE (tenant_key, brand_name, model_name, spec), UNIQUE (tenant_key, variant_code)
);

CREATE TABLE catalog_variant_color (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, variant_id BIGINT NOT NULL REFERENCES catalog_variant(id),
    color_name VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
    UNIQUE (tenant_key, variant_id, color_name)
);
