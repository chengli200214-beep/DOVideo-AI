package com.example.server.generation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.server.generation.GenerationTask.State;

/** State, trace and spending reservations commit together. No transaction spans a network call. */
@Repository
public class GenerationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<GenerationTask> mapper = (rs, row) -> new GenerationTask(
            rs.getString("id"), rs.getLong("user_id"), rs.getString("request_hash"),
            rs.getString("provider"), rs.getString("model"), rs.getString("request_json"),
            State.valueOf(rs.getString("state")), rs.getString("remote_id"), rs.getString("source_url"),
            rs.getString("artifact_key"), rs.getObject("artifact_size", Long.class),
            rs.getString("artifact_sha256"), rs.getString("error_code"), rs.getInt("attempts"),
            rs.getBoolean("recoverable"), rs.getLong("next_run_at"), rs.getString("lease_token"),
            rs.getLong("lease_until"), rs.getLong("created_at"), rs.getLong("updated_at"));

    public GenerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public void insert(String id, long user, String key, String hash, String provider,
                       String model, String json, long now) {
        insert(id, user, key, hash, provider, model, json, null, now);
    }
    public void insert(String id, long user, String key, String hash, String provider,
                       String model, String json, String originalPrompt, long now) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                INSERT INTO generation_tasks
                (id,user_id,idempotency_key,request_hash,provider,model,request_json,original_prompt,state,next_run_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'QUEUED',?,?,?)
                """, id, user, key, hash, provider, model, json, originalPrompt, now, now, now);
            event(id, State.QUEUED, null, 0, now);
        });
    }

    public GenerationTask byKey(long user, String key) {
        return one("SELECT * FROM generation_tasks WHERE user_id=? AND idempotency_key=?", user, key);
    }
    public GenerationTask byId(String id) { return one("SELECT * FROM generation_tasks WHERE id=?", id); }
    public List<GenerationTask> recent(long user) {
        return jdbc.query("SELECT * FROM generation_tasks WHERE user_id=? ORDER BY created_at DESC,id LIMIT 50", mapper, user);
    }

    public List<GenerationTask> due(long now) {
        return jdbc.query("""
                SELECT * FROM generation_tasks
                WHERE state IN ('QUEUED','SUBMITTING','RUNNING','SAVING')
                AND next_run_at<=? AND lease_until<=? ORDER BY next_run_at,id LIMIT 8
                """, mapper, now, now);
    }

    public GenerationTask claim(String id, long now) {
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
                UPDATE generation_tasks SET lease_token=?,lease_until=?,updated_at=?
                WHERE id=? AND lease_until<=? AND next_run_at<=?
                AND state IN ('QUEUED','SUBMITTING','RUNNING','SAVING')
                """, token, now + 300_000, now, id, now, now);
        return changed == 1 ? byId(id) : null;
    }

    public boolean beginSubmission(GenerationTask task, long now) {
        return beginSubmission(task, now, null, null);
    }
    public boolean beginSubmission(GenerationTask task, long now, String effectiveJson, GenerationProperties props) {
        return transaction.execute(status -> {
            int changed = jdbc.update("""
                UPDATE generation_tasks SET state='SUBMITTING',updated_at=?,effective_json=?
                WHERE id=? AND state='QUEUED' AND lease_token=? AND lease_until>?
                """, now, effectiveJson, task.id(), task.leaseToken(), now);
            if (changed != 1) return false;
            if (!task.provider().equals("mock")) {
                if (props == null) throw new IllegalArgumentException("Paid submission requires an authorization");
                props.requireAuthorization(task.model());
                var shotReservations = jdbc.queryForList("SELECT reserved_cost,authorization_id,authorization_hash FROM shot_generation_versions WHERE task_id=?", task.id());
                if (!shotReservations.isEmpty()) {
                    var expected = shotReservations.getFirst();
                    if (((java.math.BigDecimal) expected.get("reserved_cost")).compareTo(props.getReservationPerTask()) != 0
                            || !props.getAuthorizationId().equals(expected.get("authorization_id"))
                            || !props.authorizationHash().equals(expected.get("authorization_hash"))) {
                        throw new com.example.server.exception.BusinessException(com.example.server.common.ErrorCode.FORBIDDEN,
                                "镜头提交时的授权或预留金额已变化，请核对原任务");
                    }
                }
                jdbc.update("INSERT IGNORE INTO generation_authorizations(id,policy_hash) VALUES (?,?)",
                        props.getAuthorizationId(), props.authorizationHash());
                int reserved = jdbc.update("""
                    UPDATE generation_authorizations SET used_tasks=used_tasks+1,reserved_cost=reserved_cost+?
                    WHERE id=? AND used_tasks<? AND reserved_cost+?<=? AND policy_hash=?
                    """, props.getReservationPerTask(), props.getAuthorizationId(), props.getMaxPaidTasks(),
                        props.getReservationPerTask(), props.getBudgetLimit(), props.authorizationHash());
                if (reserved != 1) throw new com.example.server.exception.BusinessException(
                        com.example.server.common.ErrorCode.FORBIDDEN, "本次授权的任务数或预留预算已用尽");
                jdbc.update("INSERT INTO generation_reservations VALUES (?,?,?,?)", task.id(), props.getAuthorizationId(),
                        props.getReservationPerTask(), now);
            }
            event(task.id(), State.SUBMITTING, null, 0, now);
            return true;
        });
    }

    public boolean finish(GenerationTask task, State state, String remoteId, String sourceUrl,
                          GenerationArtifactStore.Artifact artifact, String error, int attempts,
                          boolean recoverable, long nextRun, long now) {
        return transaction.execute(status -> {
            GenerationTask before = byId(task.id());
            int changed = jdbc.update("""
                UPDATE generation_tasks SET state=?,remote_id=?,source_url=?,artifact_key=?,
                artifact_size=?,artifact_sha256=?,error_code=?,attempts=?,recoverable=?,
                next_run_at=?,lease_token=NULL,lease_until=0,updated_at=?
                WHERE id=? AND lease_token=? AND lease_until>?
                """, state.name(), remoteId, sourceUrl,
                artifact == null ? task.artifactKey() : artifact.key(),
                artifact == null ? task.artifactSize() : Long.valueOf(artifact.size()),
                artifact == null ? task.artifactSha256() : artifact.sha256(),
                error, attempts, recoverable, nextRun, now, task.id(), task.leaseToken(), now);
            if (changed == 1 && (before.state() != state || error != null || before.errorCode() != null)) {
                event(task.id(), state, error, attempts, now);
            }
            return changed == 1;
        });
    }

    public boolean retry(String id, long user, long now) {
        return transaction.execute(status -> {
            int changed = jdbc.update("""
                UPDATE generation_tasks SET state='RUNNING',attempts=0,error_code=NULL,recoverable=FALSE,
                next_run_at=?,updated_at=? WHERE id=? AND user_id=? AND state='FAILED'
                AND recoverable=TRUE AND remote_id IS NOT NULL AND lease_until<=?
                """, now, now, id, user, now);
            if (changed == 1) event(id, State.RUNNING, "MANUAL_RETRY", 0, now);
            return changed == 1;
        });
    }

    public boolean reconcile(String id, long user, String remoteId, long now) {
        return transaction.execute(status -> {
            int changed = jdbc.update("""
                UPDATE generation_tasks SET state='RUNNING',remote_id=?,attempts=0,error_code=NULL,
                next_run_at=?,updated_at=? WHERE id=? AND user_id=? AND state='SUBMISSION_UNKNOWN'
                AND lease_until<=?
                """, remoteId, now, now, id, user, now);
            if (changed == 1) event(id, State.RUNNING, "REMOTE_ID_RECONCILED", 0, now);
            return changed == 1;
        });
    }

    public java.util.Map<String, Object> details(String id) {
        return jdbc.queryForMap("SELECT original_prompt,request_json,effective_json FROM generation_tasks WHERE id=?", id);
    }
    public List<Event> events(String id) {
        return jdbc.query("SELECT * FROM generation_events WHERE task_id=? ORDER BY id", (rs, row) ->
                new Event(rs.getLong("id"), rs.getString("state"), rs.getString("error_code"),
                        rs.getInt("attempts"), rs.getLong("occurred_at")), id);
    }
    private void event(String id, State state, String error, int attempts, long now) {
        jdbc.update("INSERT INTO generation_events(task_id,state,error_code,attempts,occurred_at) VALUES (?,?,?,?,?)",
                id, state.name(), error, attempts, now);
    }
    public record Event(long id, String state, String errorCode, int attempts, long occurredAt) { }

    private GenerationTask one(String sql, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst().orElse(null);
    }
}
