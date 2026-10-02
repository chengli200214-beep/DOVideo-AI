package com.example.server.film;

import com.example.server.generation.GenerationArtifactStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

@Repository
public class CompositionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<Task> mapper = (rs, i) -> new Task(rs.getString("id"), rs.getString("project_id"), rs.getLong("user_id"),
        rs.getString("request_hash"), rs.getString("snapshot_json"), rs.getString("state"), rs.getInt("attempts"), rs.getString("error_code"),
        rs.getString("artifact_key"), rs.getObject("artifact_size",Long.class), rs.getString("artifact_sha256"), rs.getString("metadata_json"),
        rs.getString("lease_token"), rs.getLong("lease_until"), rs.getLong("created_at"), rs.getLong("updated_at"));
    public CompositionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())); }
    public Task byId(String id) { return jdbc.query("SELECT * FROM composition_tasks WHERE id=?",mapper,id).stream().findFirst().orElse(null); }
    public Task byKey(String project, String key) { return jdbc.query("SELECT * FROM composition_tasks WHERE project_id=? AND idempotency_key=?",mapper,project,key).stream().findFirst().orElse(null); }
    public List<Task> recent(String project) { return jdbc.query("SELECT * FROM composition_tasks WHERE project_id=? ORDER BY created_at DESC,id LIMIT 50",mapper,project); }
    public void insert(String id,String project,long user,String key,String hash,String snapshot,long now) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO composition_tasks(id,project_id,user_id,idempotency_key,request_hash,snapshot_json,state,next_run_at,created_at,updated_at) VALUES (?,?,?,?,?,?,'QUEUED',?,?,?)",id,project,user,key,hash,snapshot,now,now,now);
            event(id,"QUEUED",null,0,now);
        });
    }
    public List<Task> due(long now) { return jdbc.query("SELECT * FROM composition_tasks WHERE state IN ('QUEUED','RENDERING') AND next_run_at<=? AND lease_until<=? ORDER BY next_run_at,id LIMIT 1",mapper,now,now); }
    public Task claim(String id,long now) {
        return transaction.execute(tx -> {
            String lease=UUID.randomUUID().toString();
            int changed=jdbc.update("UPDATE composition_tasks SET state='RENDERING',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE id=? AND state IN ('QUEUED','RENDERING') AND lease_until<=? AND next_run_at<=?",lease,now+1_500_000,now,id,now,now);
            if(changed!=1) return null;
            Task task=byId(id); event(id,"RENDERING",null,task.attempts(),now); return task;
        });
    }
    public boolean finish(Task task,String state,GenerationArtifactStore.Artifact artifact,String metadata,String error,long next,long now) {
        return transaction.execute(tx -> {
            int changed=jdbc.update("UPDATE composition_tasks SET state=?,artifact_key=?,artifact_size=?,artifact_sha256=?,metadata_json=?,error_code=?,next_run_at=?,lease_token=NULL,lease_until=0,updated_at=? WHERE id=? AND state='RENDERING' AND lease_token=? AND lease_until>?",state,
                artifact==null?null:artifact.key(),artifact==null?null:artifact.size(),artifact==null?null:artifact.sha256(),metadata,error,next,now,task.id(),task.leaseToken(),now);
            if(changed==1) event(task.id(),state,error,task.attempts(),now);
            return changed==1;
        });
    }
    public boolean retry(String id,long user,long now) {
        return transaction.execute(tx -> {
            int changed=jdbc.update("UPDATE composition_tasks SET state='QUEUED',attempts=0,error_code=NULL,next_run_at=?,updated_at=? WHERE id=? AND user_id=? AND state='FAILED' AND lease_until<=?",now,now,id,user,now);
            if(changed==1) event(id,"QUEUED","MANUAL_RETRY",0,now); return changed==1;
        });
    }
    private void event(String id,String state,String error,int attempts,long now) { jdbc.update("INSERT INTO composition_events(task_id,state,error_code,attempts,occurred_at) VALUES (?,?,?,?,?)",id,state,error,attempts,now); }
    public List<Event> events(String id) { return jdbc.query("SELECT * FROM composition_events WHERE task_id=? ORDER BY id",(rs,i)->new Event(rs.getString("state"),rs.getString("error_code"),rs.getInt("attempts"),rs.getLong("occurred_at")),id); }
    public record Event(String state,String errorCode,int attempts,long occurredAt) { }
    public record Task(String id,String projectId,long userId,String requestHash,String snapshotJson,String state,int attempts,String errorCode,
                       String artifactKey,Long artifactSize,String artifactSha256,String metadataJson,String leaseToken,long leaseUntil,long createdAt,long updatedAt) { }
}
