CREATE TABLE creative_projects (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    brief_hash VARCHAR(64) NOT NULL,
    brief_json LONGTEXT NOT NULL,
    latest_revision INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    confirmed_revision INT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_creative_project_intent (user_id, idempotency_key),
    KEY idx_creative_project_owner (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE storyboard_revisions (
    project_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    parent_revision INT NULL,
    origin VARCHAR(32) NOT NULL,
    planner VARCHAR(64) NOT NULL,
    draft_json LONGTEXT NOT NULL,
    validation_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (project_id, revision),
    FOREIGN KEY (project_id) REFERENCES creative_projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE storyboard_planning_attempts (
    project_id VARCHAR(36) NOT NULL,
    attempt INT NOT NULL,
    draft_json LONGTEXT NOT NULL,
    validation_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (project_id, attempt),
    FOREIGN KEY (project_id) REFERENCES creative_projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE storyboard_confirmations (
    project_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    confirmed_at BIGINT NOT NULL,
    PRIMARY KEY (project_id, revision),
    FOREIGN KEY (project_id, revision) REFERENCES storyboard_revisions(project_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
