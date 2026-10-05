package com.example.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.NoSuchElementException;
import static com.example.server.service.UrlIngestService.*;

/** The database is authoritative; Redis remains a best-effort compatibility cache. */
@Repository
public class UrlIngestJobRepository {
    private final JdbcTemplate jdbc;
    public UrlIngestJobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Job find(String id) {
        return jdbc.query("SELECT * FROM media_url_ingest_jobs WHERE id=?", (rs, row) ->
                new Job(rs.getString("id"), rs.getLong("user_id"), Status.valueOf(rs.getString("status")),
                        rs.getObject("media_id", Long.class), rs.getString("error"), rs.getLong("created_at"), rs.getLong("updated_at")), id)
                .stream().findFirst().orElse(null);
    }
    public void create(Job job) {
        jdbc.update("INSERT INTO media_url_ingest_jobs VALUES (?,?,?,?,?,?,?)", job.id(), job.userId(), job.status().name(),
                job.mediaId(), job.error(), job.createdAt(), job.updatedAt());
    }
    public boolean start(Job job) {
        return jdbc.update("UPDATE media_url_ingest_jobs SET status='RUNNING',updated_at=? WHERE id=? AND status='QUEUED'", job.updatedAt(), job.id()) == 1;
    }
    public void update(Job job) {
        if (jdbc.update("UPDATE media_url_ingest_jobs SET status=?,media_id=?,error=?,updated_at=? WHERE id=? AND user_id=?",
                job.status().name(), job.mediaId(), job.error()==null?null:job.error().substring(0, Math.min(1000, job.error().length())),
                job.updatedAt(), job.id(), job.userId()) != 1) throw new NoSuchElementException("链接下载任务不存在");
    }
}
