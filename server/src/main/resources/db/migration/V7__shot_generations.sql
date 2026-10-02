CREATE TABLE project_generation_budgets (
    project_id VARCHAR(36) NOT NULL PRIMARY KEY,
    max_versions INT NOT NULL,
    cost_limit DECIMAL(18,6) NOT NULL,
    used_versions INT NOT NULL DEFAULT 0,
    reserved_cost DECIMAL(18,6) NOT NULL DEFAULT 0,
    FOREIGN KEY (project_id) REFERENCES creative_projects(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shot_generation_operations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    mode VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_shot_operation_intent (project_id, idempotency_key),
    FOREIGN KEY (project_id, revision) REFERENCES storyboard_revisions(project_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE shot_generation_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    shot_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    shot_json LONGTEXT NOT NULL,
    reserved_cost DECIMAL(18,6) NOT NULL,
    authorization_id VARCHAR(128) NULL,
    authorization_hash VARCHAR(64) NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY uk_shot_version (project_id, revision, shot_id, version),
    UNIQUE KEY uk_shot_generation_task (task_id),
    FOREIGN KEY (project_id, revision) REFERENCES storyboard_revisions(project_id, revision),
    FOREIGN KEY (operation_id) REFERENCES shot_generation_operations(id),
    FOREIGN KEY (task_id) REFERENCES generation_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
