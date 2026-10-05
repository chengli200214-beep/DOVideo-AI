-- Media identity and completion commit in a single database insert, independent of Redis.
ALTER TABLE media_files ADD COLUMN ingest_key VARCHAR(96) NULL;
CREATE UNIQUE INDEX uk_media_ingest_key ON media_files(ingest_key);

CREATE TABLE media_url_ingest_jobs (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    media_id BIGINT NULL,
    error VARCHAR(1000) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    KEY idx_url_ingest_user (user_id, created_at),
    KEY idx_url_ingest_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
