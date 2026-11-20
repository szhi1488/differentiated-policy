CREATE TABLE IF NOT EXISTS user_info (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_key VARCHAR(64) NOT NULL,
  open_id VARCHAR(64) NOT NULL,
  union_id VARCHAR(64),
  name VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(2048),
  department VARCHAR(128),
  role_type VARCHAR(32) NOT NULL DEFAULT 'APPLICANT',
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (tenant_key, open_id),
  UNIQUE (id, tenant_key)
);

CREATE TABLE IF NOT EXISTS user_platform_assignment (
  tenant_key VARCHAR(64) NOT NULL,
  perspective VARCHAR(16) NOT NULL,
  platform_code VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY (tenant_key, perspective, platform_code),
  FOREIGN KEY (user_id, tenant_key) REFERENCES user_info(id, tenant_key)
);
