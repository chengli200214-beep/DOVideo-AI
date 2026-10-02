ALTER TABLE generation_tasks ADD COLUMN original_prompt TEXT NULL;
ALTER TABLE generation_tasks ADD COLUMN effective_json LONGTEXT NULL;

CREATE TABLE generation_assets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    mime VARCHAR(32) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_generation_asset_content (user_id, sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    attempts INT NOT NULL,
    occurred_at BIGINT NOT NULL,
    KEY idx_generation_event_task (task_id, id),
    FOREIGN KEY (task_id) REFERENCES generation_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_authorizations (
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    policy_hash VARCHAR(64) NOT NULL,
    used_tasks INT NOT NULL DEFAULT 0,
    reserved_cost DECIMAL(18,6) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_reservations (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    authorization_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_cost DECIMAL(18,6) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES generation_tasks(id),
    FOREIGN KEY (authorization_id) REFERENCES generation_authorizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
