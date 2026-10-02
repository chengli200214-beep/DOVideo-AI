CREATE TABLE storyboard_planning_authorizations (
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    policy_hash VARCHAR(64) NOT NULL,
    used_calls INT NOT NULL DEFAULT 0,
    reserved_cost DECIMAL(18,6) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE storyboard_model_tasks (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_revision INT NOT NULL,
    authorization_id VARCHAR(128) NOT NULL,
    policy_hash VARCHAR(64) NOT NULL,
    model VARCHAR(80) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reserved_cost DECIMAL(18,6) NOT NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until BIGINT NULL,
    response_id VARCHAR(128) NULL,
    response_json LONGTEXT NULL,
    result_json LONGTEXT NULL,
    validation_json TEXT NULL,
    usage_json TEXT NULL,
    estimated_cost DECIMAL(18,6) NULL,
    saved_revision INT NULL,
    error_code VARCHAR(40) NULL,
    created_at BIGINT NOT NULL,
    started_at BIGINT NULL,
    finished_at BIGINT NULL,
    UNIQUE KEY uk_storyboard_model_intent (user_id, idempotency_key),
    KEY idx_storyboard_model_owner (project_id, user_id, created_at),
    KEY idx_storyboard_model_due (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
