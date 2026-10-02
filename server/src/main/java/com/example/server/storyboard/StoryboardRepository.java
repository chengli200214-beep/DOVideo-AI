package com.example.server.storyboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class StoryboardRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<Project> projectMapper = (rs, row) -> new Project(rs.getString("id"), rs.getLong("user_id"),
            rs.getString("brief_hash"), rs.getString("brief_json"), rs.getInt("latest_revision"), rs.getString("status"),
            rs.getObject("confirmed_revision", Integer.class), rs.getLong("created_at"), rs.getLong("updated_at"));
    private final RowMapper<Revision> revisionMapper = (rs, row) -> new Revision(rs.getInt("revision"),
            rs.getObject("parent_revision", Integer.class), rs.getString("origin"), rs.getString("planner"),
            rs.getString("draft_json"), rs.getString("validation_json"), rs.getLong("created_at"));
    public StoryboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public Project byId(String id, long user) {
        return jdbc.query("SELECT * FROM creative_projects WHERE id=? AND user_id=?", projectMapper, id, user).stream().findFirst().orElse(null);
    }
    public Project byKey(long user, String key) {
        return jdbc.query("SELECT * FROM creative_projects WHERE user_id=? AND idempotency_key=?", projectMapper, user, key).stream().findFirst().orElse(null);
    }
    public List<Project> recent(long user) {
        return jdbc.query("SELECT * FROM creative_projects WHERE user_id=? ORDER BY updated_at DESC,id LIMIT 50", projectMapper, user);
    }
    public List<Revision> revisions(String id) {
        return jdbc.query("SELECT * FROM storyboard_revisions WHERE project_id=? ORDER BY revision", revisionMapper, id);
    }
    public Revision revision(String id, int number) {
        return jdbc.query("SELECT * FROM storyboard_revisions WHERE project_id=? AND revision=?", revisionMapper, id, number).getFirst();
    }
    public List<Attempt> attempts(String id) {
        return jdbc.query("SELECT * FROM storyboard_planning_attempts WHERE project_id=? ORDER BY attempt", (rs, row) ->
                new Attempt(rs.getInt("attempt"), rs.getString("draft_json"), rs.getString("validation_json")), id);
    }
    public List<Confirmation> confirmations(String id) {
        return jdbc.query("SELECT * FROM storyboard_confirmations WHERE project_id=? ORDER BY confirmed_at,revision", (rs, row) ->
                new Confirmation(rs.getInt("revision"), rs.getLong("confirmed_at")), id);
    }
    public void create(String id, long user, String key, String hash, String brief, String status,
                       Revision revision, List<Attempt> attempts, long now) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO creative_projects VALUES (?,?,?,?,?,1,?,NULL,?,?)", id, user, key, hash, brief, status, now, now);
            insertRevision(id, revision);
            for (Attempt attempt : attempts) jdbc.update("INSERT INTO storyboard_planning_attempts VALUES (?,?,?,?,?)",
                    id, attempt.number(), attempt.json(), attempt.validationJson(), now);
        });
    }
    public boolean edit(String id, long user, int expected, String draft, long now) {
        return transaction.execute(tx -> {
            int changed = jdbc.update("""
                UPDATE creative_projects SET latest_revision=latest_revision+1,status='DRAFT',confirmed_revision=NULL,updated_at=?
                WHERE id=? AND user_id=? AND latest_revision=?
                """, now, id, user, expected);
            if (changed != 1) return false;
            insertRevision(id, new Revision(expected + 1, expected, "USER", "manual", draft, "[]", now));
            return true;
        });
    }
    public boolean confirm(String id, long user, int expected, long now) {
        return transaction.execute(tx -> {
            int changed = jdbc.update("""
                UPDATE creative_projects SET status='CONFIRMED',confirmed_revision=?,updated_at=?
                WHERE id=? AND user_id=? AND latest_revision=?
                """, expected, now, id, user, expected);
            if (changed != 1) return false;
            jdbc.update("INSERT IGNORE INTO storyboard_confirmations VALUES (?,?,?)", id, expected, now);
            return true;
        });
    }
    private void insertRevision(String id, Revision revision) {
        jdbc.update("INSERT INTO storyboard_revisions VALUES (?,?,?,?,?,?,?,?)", id, revision.number(), revision.parent(),
                revision.origin(), revision.planner(), revision.json(), revision.validationJson(), revision.createdAt());
    }
    public record Project(String id, long user, String briefHash, String briefJson, int latestRevision, String status,
                          Integer confirmedRevision, long createdAt, long updatedAt) { }
    public record Revision(int number, Integer parent, String origin, String planner, String json, String validationJson, long createdAt) { }
    public record Attempt(int number, String json, String validationJson) { }
    public record Confirmation(int revision, long confirmedAt) { }
}
