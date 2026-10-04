package com.example.server.storyboard;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.*;

@Repository
public class StoryboardModelRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RowMapper<Task> mapper=(rs,row)->new Task(rs.getString("id"),rs.getString("project_id"),rs.getLong("user_id"),
            rs.getString("idempotency_key"),rs.getInt("expected_revision"),rs.getString("policy_hash"),rs.getString("model"),
            rs.getString("currency"),rs.getBigDecimal("reserved_cost"),rs.getString("request_json"),rs.getString("status"),
            rs.getString("lease_token"),rs.getObject("lease_until",Long.class),rs.getString("response_id"),rs.getString("response_json"),rs.getString("result_json"),
            rs.getString("validation_json"),rs.getString("usage_json"),rs.getBigDecimal("estimated_cost"),rs.getObject("saved_revision",Integer.class),rs.getString("error_code"),
            rs.getLong("created_at"),rs.getObject("started_at",Long.class),rs.getObject("finished_at",Long.class));
    public StoryboardModelRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())); }
    public Task byKey(long user,String key) { return jdbc.query("SELECT * FROM storyboard_model_tasks WHERE user_id=? AND idempotency_key=?",mapper,user,key).stream().findFirst().orElse(null); }
    public Task owned(long user,String project,String id) {
        return jdbc.query("SELECT * FROM storyboard_model_tasks WHERE user_id=? AND project_id=? AND id=?",mapper,user,project,id).stream().findFirst().orElseThrow(()->new NoSuchElementException("分镜模型任务不存在"));
    }
    public List<Task> recent(long user,String project) { return jdbc.query("SELECT * FROM storyboard_model_tasks WHERE user_id=? AND project_id=? ORDER BY created_at DESC,id LIMIT 50",mapper,user,project); }
    public Task create(long user,String project,String key,int expected,String request,StoryboardModelProperties p,long now) {
        return tx.execute(transaction->{
            var revisions=jdbc.queryForList("SELECT latest_revision,archived_at FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE",project,user);
            if (revisions.isEmpty()) throw new NoSuchElementException("创作项目不存在");
            Task prior=byKey(user,key);
            if (prior!=null) { requireSame(prior,project,expected); return prior; }
            if (revisions.getFirst().get("archived_at") != null) throw conflict("项目已归档，请恢复后再生成");
            if (((Number)revisions.getFirst().get("latest_revision")).intValue()!=expected) throw conflict("分镜版本已变化，请重新载入后生成");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_model_tasks WHERE project_id=? AND status IN ('QUEUED','SUBMITTING')",Integer.class,project)>0)
                throw conflict("该项目已有分镜模型任务，请先查询结果");
            p.requireEnabled();
            jdbc.update("INSERT IGNORE INTO storyboard_planning_authorizations(id,policy_hash) VALUES (?,?)",p.getAuthorizationId(),p.policyHash());
            int changed=jdbc.update("""
                UPDATE storyboard_planning_authorizations SET used_calls=used_calls+1,reserved_cost=reserved_cost+?
                WHERE id=? AND policy_hash=? AND used_calls<? AND reserved_cost+?<=?
                """,p.getReservationPerCall(),p.getAuthorizationId(),p.policyHash(),p.getMaxCalls(),p.getReservationPerCall(),p.getBudgetLimit());
            if (changed!=1) throw conflict("分镜调用次数或预算已用尽，或同一授权的配置发生变化");
            String id=UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO storyboard_model_tasks(id,project_id,user_id,idempotency_key,expected_revision,authorization_id,policy_hash,
                  model,currency,reserved_cost,request_json,status,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?)
                """,id,project,user,key,expected,p.getAuthorizationId(),p.policyHash(),p.getModel(),p.getCurrency(),p.getReservationPerCall(),request,now);
            return byKey(user,key);
        });
    }
    public List<String> due() { return jdbc.query("SELECT id FROM storyboard_model_tasks WHERE status='QUEUED' ORDER BY created_at,id LIMIT 5",(rs,row)->rs.getString(1)); }
    public Task claim(String id,long now) {
        String lease=UUID.randomUUID().toString();
        int changed=jdbc.update("UPDATE storyboard_model_tasks SET status='SUBMITTING',lease_token=?,lease_until=?,started_at=? WHERE id=? AND status='QUEUED'",lease,now+300_000,now,id);
        return changed==1 ? jdbc.queryForObject("SELECT * FROM storyboard_model_tasks WHERE id=?",mapper,id) : null;
    }
    /** Expired submissions may have been billed. Recovery records uncertainty and NEVER issues another POST. */
    public void recover(long now) {
        jdbc.update("UPDATE storyboard_model_tasks SET status='UNKNOWN',error_code='INTERRUPTED',finished_at=?,lease_token=NULL,lease_until=NULL WHERE status='SUBMITTING' AND lease_until<?",now,now);
    }
    public void fail(Task task,String status,String code,long now) {
        jdbc.update("UPDATE storyboard_model_tasks SET status=?,error_code=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND status='SUBMITTING' AND lease_token=?",
                status,code,now,task.id(),task.leaseToken());
    }
    public void finish(Task task,DeepSeekStoryboardPlanner.Completion output,String draftJson,String validationJson,boolean valid,long now) {
        tx.executeWithoutResult(transaction->{
            var current=jdbc.query("SELECT * FROM storyboard_model_tasks WHERE id=? FOR UPDATE",mapper,task.id()).getFirst();
            if (!"SUBMITTING".equals(current.status()) || !Objects.equals(task.leaseToken(),current.leaseToken()) || current.leaseUntil()==null || current.leaseUntil()<now) return;
            var projects=jdbc.queryForList("SELECT latest_revision FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE",task.projectId(),task.userId());
            boolean fresh=!projects.isEmpty() && ((Number)projects.getFirst().get("latest_revision")).intValue()==task.expectedRevision();
            String status=!valid ? "FAILED" : fresh ? "SUCCEEDED" : "CONFLICT";
            String error=!valid ? output.errorCode()==null ? "VALIDATION_FAILED" : output.errorCode() : fresh ? null : "REVISION_CHANGED";
            Integer saved=valid && fresh ? task.expectedRevision()+1 : null;
            if (saved!=null) {
                com.example.server.generation.GenerationAssetReferences.attach(jdbc,task.userId(),"REVISION",task.projectId()+":"+saved,draftJson);
                jdbc.update("INSERT INTO storyboard_revisions VALUES (?,?,?,?,?,?,?,?)",task.projectId(),saved,task.expectedRevision(),"MODEL","deepseek-api-v1",draftJson,validationJson,now);
                jdbc.update("UPDATE creative_projects SET latest_revision=?,status='DRAFT',confirmed_revision=NULL,updated_at=? WHERE id=?",saved,now,task.projectId());
            }
            jdbc.update("""
                UPDATE storyboard_model_tasks SET status=?,response_id=?,response_json=?,result_json=?,validation_json=?,usage_json=?,estimated_cost=?,saved_revision=?,
                    error_code=?,finished_at=?,lease_token=NULL,lease_until=NULL WHERE id=?
                """,status,output.responseId(),output.responseJson(),draftJson,validationJson,output.usageJson(),output.estimatedCost(),saved,error,now,task.id());
        });
    }
    public Budget budget(String authorization) {
        return jdbc.query("SELECT used_calls,reserved_cost,policy_hash FROM storyboard_planning_authorizations WHERE id=?",(rs,row)->new Budget(rs.getInt(1),rs.getBigDecimal(2),rs.getString(3)),authorization)
                .stream().findFirst().orElse(new Budget(0,BigDecimal.ZERO,null));
    }
    public static void requireSame(Task task,String project,int revision) { if (!task.projectId().equals(project) || task.expectedRevision()!=revision) throw conflict("同一 Idempotency-Key 不可用于不同分镜生成意图"); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
    public record Budget(int usedCalls,BigDecimal reservedCost,String policyHash) { }
    public record Task(String id,String projectId,long userId,String idempotencyKey,int expectedRevision,String policyHash,String model,
            String currency,BigDecimal reservedCost,String requestJson,String status,
            @com.fasterxml.jackson.annotation.JsonIgnore String leaseToken,@com.fasterxml.jackson.annotation.JsonIgnore Long leaseUntil,String responseId,String responseJson,
            String resultJson,String validationJson,String usageJson,BigDecimal estimatedCost,Integer savedRevision,String errorCode,long createdAt,Long startedAt,Long finishedAt) { }
}
