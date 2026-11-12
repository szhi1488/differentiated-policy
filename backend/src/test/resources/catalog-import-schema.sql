-- 只在隔离的 H2 测试库使用；生产库由 docs/sql/schema.sql 维护。
CREATE TABLE goods_sync_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, source_key VARCHAR(64) NOT NULL,
    requester_id BIGINT NOT NULL, brand_name VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    last_max_id VARCHAR(32) NOT NULL DEFAULT '0', page_size INT NOT NULL, include_blockup TINYINT NOT NULL,
    page_count INT NOT NULL DEFAULT 0, read_count BIGINT NOT NULL DEFAULT 0, content_count BIGINT NOT NULL DEFAULT 0,
    clean_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED', failure_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64), worker_token VARCHAR(64), lease_until DATETIME, next_request_at DATETIME NOT NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0, record_version INT NOT NULL DEFAULT 0,
    started_at DATETIME, finished_at DATETIME,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('QUEUED', 'RUNNING', 'PAUSED') THEN 1 ELSE NULL END),
    UNIQUE (tenant_key, source_key, idempotency_key), UNIQUE (tenant_key, source_key, active_slot),
    UNIQUE (id, tenant_key, source_key, brand_name)
);

CREATE TABLE source_goods_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, source_key VARCHAR(64) NOT NULL, brand_name VARCHAR(32) NOT NULL,
    goods_name VARCHAR(512), goods_no VARCHAR(128), sku_name VARCHAR(256), cate_name VARCHAR(256),
    goods_field4 VARCHAR(1024), goods_field1 VARCHAR(1024), goods_field5 VARCHAR(1024),
    content_hash CHAR(64) NOT NULL, collision_no INT NOT NULL, first_seen_job_id BIGINT NOT NULL,
    last_seen_job_id BIGINT NOT NULL, record_version INT NOT NULL DEFAULT 0,
    clean_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    UNIQUE (tenant_key, source_key, brand_name, content_hash, collision_no),
    FOREIGN KEY (first_seen_job_id, tenant_key, source_key, brand_name) REFERENCES goods_sync_job (id, tenant_key, source_key, brand_name),
    FOREIGN KEY (last_seen_job_id, tenant_key, source_key, brand_name) REFERENCES goods_sync_job (id, tenant_key, source_key, brand_name)
);

CREATE TABLE catalog_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(64),
    entity_type VARCHAR(32) NOT NULL, entity_id BIGINT NOT NULL, action_type VARCHAR(32) NOT NULL
);
