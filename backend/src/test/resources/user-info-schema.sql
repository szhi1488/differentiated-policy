CREATE TABLE IF NOT EXISTS user_info (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_key VARCHAR(64) NOT NULL,
  open_id VARCHAR(64) NOT NULL,
  union_id VARCHAR(64),
  name VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(2048),
  department VARCHAR(128),
  auth_version INT NOT NULL DEFAULT 1,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (tenant_key, open_id),
  UNIQUE (id, tenant_key)
);

CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_key VARCHAR(64) NOT NULL,
  role_code VARCHAR(64) NOT NULL,
  role_name VARCHAR(64) NOT NULL,
  is_builtin TINYINT NOT NULL DEFAULT 0,
  is_enabled TINYINT NOT NULL DEFAULT 1,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE (tenant_key, role_code),
  UNIQUE (id, tenant_key),
  CONSTRAINT ck_sys_role_code CHECK (
    CHAR_LENGTH(role_code) > 0 AND CHAR_LENGTH(role_code) = CHAR_LENGTH(TRIM(role_code))),
  CONSTRAINT ck_sys_role_name CHECK (CHAR_LENGTH(TRIM(role_name)) > 0),
  CONSTRAINT ck_sys_role_flags CHECK (is_builtin IN (0, 1) AND is_enabled IN (0, 1)),
  CONSTRAINT ck_sys_role_admin CHECK (
    role_code <> 'ADMIN' OR (is_builtin = 1 AND is_enabled = 1)),
  CONSTRAINT ck_sys_role_version CHECK (version >= 1)
);

CREATE TABLE IF NOT EXISTS sys_user_role (
  tenant_key VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (tenant_key, user_id, role_id),
  CONSTRAINT fk_sys_user_role_account FOREIGN KEY (user_id, tenant_key)
    REFERENCES user_info (id, tenant_key),
  CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id, tenant_key)
    REFERENCES sys_role (id, tenant_key)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
  tenant_key VARCHAR(64) NOT NULL,
  role_id BIGINT NOT NULL,
  permission_code VARCHAR(80) NOT NULL,
  PRIMARY KEY (tenant_key, role_id, permission_code),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id, tenant_key)
    REFERENCES sys_role (id, tenant_key),
  CONSTRAINT ck_role_permission_code CHECK (
    CHAR_LENGTH(permission_code) > 0 AND CHAR_LENGTH(permission_code) = CHAR_LENGTH(TRIM(permission_code)))
);

CREATE TABLE IF NOT EXISTS platform_team (
  tenant_key VARCHAR(64) NOT NULL,
  perspective VARCHAR(16) NOT NULL,
  platform_code VARCHAR(32) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  updated_by BIGINT DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_key, perspective, platform_code),
  CONSTRAINT fk_platform_team_editor FOREIGN KEY (updated_by, tenant_key)
    REFERENCES user_info (id, tenant_key),
  CONSTRAINT ck_platform_team_channel CHECK (
    (perspective = 'RETAIL' AND platform_code IN
      ('KUAISHOU','PDD','JD','DOUYIN','TMALL','TAOBAO','ZHAOSHANG','DEWU','UNICOM','GOV_ENTERPRISE'))
    OR (perspective = 'BILLION' AND platform_code IN ('PDD','TAOBAO','UNICOM')))
);

CREATE TABLE IF NOT EXISTS user_platform_assignment (
  tenant_key VARCHAR(64) NOT NULL,
  perspective VARCHAR(16) NOT NULL,
  platform_code VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  member_kind VARCHAR(16) NOT NULL,
  submitter_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN member_kind = 'SUBMITTER' THEN 1 ELSE NULL END),
  PRIMARY KEY (tenant_key, perspective, platform_code, user_id),
  UNIQUE (tenant_key, perspective, platform_code, submitter_slot),
  CONSTRAINT fk_assignment_team FOREIGN KEY (tenant_key, perspective, platform_code)
    REFERENCES platform_team (tenant_key, perspective, platform_code),
  CONSTRAINT fk_assignment_user FOREIGN KEY (user_id, tenant_key)
    REFERENCES user_info (id, tenant_key),
  CONSTRAINT ck_assignment_member_kind CHECK (member_kind IN ('SUBMITTER', 'MEMBER'))
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
