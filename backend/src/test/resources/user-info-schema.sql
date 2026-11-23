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

CREATE TABLE IF NOT EXISTS policy_review_settings (
  tenant_key VARCHAR(64) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  updated_by BIGINT DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_key),
  CONSTRAINT ck_review_settings_version CHECK (version >= 0),
  CONSTRAINT fk_review_settings_editor FOREIGN KEY (updated_by, tenant_key) REFERENCES user_info (id, tenant_key)
);

CREATE TABLE IF NOT EXISTS policy_review_setting_member (
  tenant_key VARCHAR(64) NOT NULL,
  group_code VARCHAR(16) NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY (tenant_key, group_code, user_id),
  CONSTRAINT ck_review_setting_group CHECK (group_code IN ('PRODUCT','PURCHASE')),
  CONSTRAINT fk_review_setting_parent FOREIGN KEY (tenant_key) REFERENCES policy_review_settings (tenant_key),
  CONSTRAINT fk_review_setting_user FOREIGN KEY (user_id, tenant_key) REFERENCES user_info (id, tenant_key)
);
