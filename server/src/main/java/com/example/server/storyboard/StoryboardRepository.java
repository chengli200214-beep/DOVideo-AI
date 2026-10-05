package com.example.server.storyboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.generation.GenerationAssetReferences;
import java.util.List;

@Repository
public class StoryboardRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<Project> projectMapper = (rs, row) -> new Project(rs.getString("id"), rs.getLong("user_id"),
            rs.getString("brief_hash"), rs.getString("brief_json"), rs.getInt("latest_revision"), rs.getString("status"),
            rs.getObject("confirmed_revision", Integer.class), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getObject("archived_at", Long.class));
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
        return jdbc.query("SELECT * FROM creative_projects WHERE user_id=? AND archived_at IS NULL ORDER BY updated_at DESC,id LIMIT 50", projectMapper, user);
    }
    public List<Project> page(long user, String query, boolean archived, int limit, Long beforeTime, String beforeId) {
        var args = new java.util.ArrayList<Object>(); args.add(user);
        String sql = "SELECT * FROM creative_projects WHERE user_id=? AND archived_at IS " + (archived ? "NOT NULL" : "NULL");
        if (!query.isBlank()) {
            sql += " AND (LOWER(brief_json) LIKE ? ESCAPE '!' OR id=?)";
            args.add("%" + query.toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"); args.add(query);
        }
        if (beforeTime != null) { sql += " AND (updated_at<? OR (updated_at=? AND id>?))"; args.add(beforeTime); args.add(beforeTime); args.add(beforeId); }
        sql += " ORDER BY updated_at DESC,id LIMIT ?"; args.add(limit);
        return jdbc.query(sql, projectMapper, args.toArray());
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
            jdbc.update("INSERT INTO creative_projects(id,user_id,idempotency_key,brief_hash,brief_json,latest_revision,status,confirmed_revision,created_at,updated_at) VALUES (?,?,?,?,?,1,?,NULL,?,?)", id, user, key, hash, brief, status, now, now);
            GenerationAssetReferences.attach(jdbc, user, "PROJECT", id, brief, revision.json());
            insertRevision(id, revision);
            for (Attempt attempt : attempts) jdbc.update("INSERT INTO storyboard_planning_attempts VALUES (?,?,?,?,?)",
                    id, attempt.number(), attempt.json(), attempt.validationJson(), now);
        });
    }
    public boolean edit(String id, long user, int expected, String draft, long now) {
        return transaction.execute(tx -> {
            int changed = jdbc.update("""
                UPDATE creative_projects SET latest_revision=latest_revision+1,status='DRAFT',confirmed_revision=NULL,updated_at=?
                WHERE id=? AND user_id=? AND latest_revision=? AND archived_at IS NULL
                """, now, id, user, expected);
            if (changed != 1) return false;
            GenerationAssetReferences.attach(jdbc, user, "REVISION", id + ":" + (expected + 1), draft);
            insertRevision(id, new Revision(expected + 1, expected, "USER", "manual", draft, "[]", now));
            return true;
        });
    }
    public boolean confirm(String id, long user, int expected, long now) {
        return transaction.execute(tx -> {
            if (jdbc.queryForList("SELECT id FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE", id, user).isEmpty()) return false;
            requireNoActivePlanning(jdbc, id);
            int changed = jdbc.update("""
                UPDATE creative_projects SET status='CONFIRMED',confirmed_revision=?,updated_at=?
                WHERE id=? AND user_id=? AND latest_revision=? AND archived_at IS NULL
                """, expected, now, id, user, expected);
            if (changed != 1) return false;
            jdbc.update("INSERT IGNORE INTO storyboard_confirmations VALUES (?,?,?)", id, expected, now);
            return true;
        });
    }
    public static void requireNoActivePlanning(JdbcTemplate jdbc, String id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_model_tasks WHERE project_id=? AND status IN ('QUEUED','SUBMITTING')", Integer.class, id) > 0)
            throw new BusinessException(ErrorCode.CONFLICT, "分镜模型仍在生成，请等待结果后再确认或提交镜头");
    }
    public void archive(long user, String id, boolean archived, long now) {
        transaction.executeWithoutResult(tx -> {
            if (jdbc.queryForList("SELECT id FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE", id, user).isEmpty())
                throw new java.util.NoSuchElementException("创作项目不存在");
            if (archived) {
                int videos = jdbc.queryForObject("SELECT COUNT(*) FROM shot_generation_versions v JOIN generation_tasks t ON t.id=v.task_id WHERE v.project_id=? AND t.state IN ('QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN')", Integer.class, id);
                int plans = jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_model_tasks WHERE project_id=? AND status IN ('QUEUED','SUBMITTING','UNKNOWN')", Integer.class, id);
                int films = jdbc.queryForObject("SELECT COUNT(*) FROM composition_tasks WHERE project_id=? AND state IN ('QUEUED','RENDERING')", Integer.class, id);
                if (videos + plans + films > 0) throw new BusinessException(ErrorCode.CONFLICT, "项目仍有进行中或结果未知的任务，请保留项目以便查询");
            }
            jdbc.update("UPDATE creative_projects SET archived_at=?,updated_at=? WHERE id=?", archived ? now : null, now, id);
        });
    }
    public static void requireActive(Project project) {
        if (project.archivedAt() != null) throw new BusinessException(ErrorCode.CONFLICT, "项目已归档，请恢复后再编辑或生成");
    }
    private void insertRevision(String id, Revision revision) {
        jdbc.update("INSERT INTO storyboard_revisions VALUES (?,?,?,?,?,?,?,?)", id, revision.number(), revision.parent(),
                revision.origin(), revision.planner(), revision.json(), revision.validationJson(), revision.createdAt());
    }
    public record Project(String id, long user, String briefHash, String briefJson, int latestRevision, String status,
                          Integer confirmedRevision, long createdAt, long updatedAt, Long archivedAt) { }
    public record Revision(int number, Integer parent, String origin, String planner, String json, String validationJson, long createdAt) { }
    public record Attempt(int number, String json, String validationJson) { }
    public record Confirmation(int revision, long confirmedAt) { }
}
