ALTER TABLE creative_projects ADD COLUMN archived_at BIGINT NULL;
CREATE INDEX idx_project_library ON creative_projects(user_id, archived_at, updated_at, id);

CREATE TABLE generation_asset_references (
    asset_id VARCHAR(36) NOT NULL,
    owner_type VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (asset_id, owner_type, owner_id),
    FOREIGN KEY (asset_id) REFERENCES generation_assets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_asset_cleanup (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36) NULL,
    next_run_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    finished_at BIGINT NULL,
    KEY idx_asset_cleanup_due (state, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
