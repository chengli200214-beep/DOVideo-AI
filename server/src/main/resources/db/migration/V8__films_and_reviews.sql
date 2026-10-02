CREATE TABLE film_selections (
    project_id VARCHAR(36) NOT NULL,
    selection_version INT NOT NULL,
    revision INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (project_id, selection_version),
    FOREIGN KEY (project_id, revision) REFERENCES storyboard_revisions(project_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE composition_tasks (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    artifact_key VARCHAR(255) NULL,
    artifact_size BIGINT NULL,
    artifact_sha256 VARCHAR(64) NULL,
    metadata_json LONGTEXT NULL,
    next_run_at BIGINT NOT NULL,
    lease_token VARCHAR(36) NULL,
    lease_until BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_composition_intent (project_id,idempotency_key),
    KEY idx_composition_due (state,next_run_at,lease_until),
    FOREIGN KEY (project_id) REFERENCES creative_projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE composition_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    attempts INT NOT NULL,
    occurred_at BIGINT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES composition_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE generation_quality_reviews (
    version_id VARCHAR(36) NOT NULL,
    review_version INT NOT NULL,
    content_score INT NOT NULL,
    motion_score INT NOT NULL,
    consistency_score INT NOT NULL,
    usable BOOLEAN NOT NULL,
    notes TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (version_id,review_version),
    FOREIGN KEY (version_id) REFERENCES shot_generation_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
