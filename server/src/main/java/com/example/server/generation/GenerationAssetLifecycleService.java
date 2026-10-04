package com.example.server.generation;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Explicit deletion only; historic references and generated video artifacts are retained. */
@Service
public class GenerationAssetLifecycleService {
    private final JdbcTemplate jdbc;
    private final GenerationAssetService assets;
    private final GenerationArtifactStore store;
    private final TransactionTemplate tx;

    public GenerationAssetLifecycleService(JdbcTemplate jdbc, GenerationAssetService assets, GenerationArtifactStore store) {
        this.jdbc=jdbc; this.assets=assets; this.store=store;
        tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public Page page(long user, int limit, Long beforeTime, String beforeId) {
        if (limit<1 || limit>50 || (beforeTime==null)!=(beforeId==null)
                || beforeTime!=null && (beforeTime<0 || !beforeId.matches("[a-fA-F0-9-]{36}")))
            throw new IllegalArgumentException("素材查询参数不合法");
        var args=new ArrayList<Object>(); args.add(user);
        String sql="SELECT id FROM generation_assets WHERE user_id=?";
        if(beforeTime!=null) { sql+=" AND (created_at<? OR (created_at=? AND id>?))"; args.add(beforeTime); args.add(beforeTime); args.add(beforeId); }
        sql+=" ORDER BY created_at DESC,id LIMIT ?"; args.add(limit+1);
        var ids=jdbc.queryForList(sql,String.class,args.toArray());
        var items=ids.stream().limit(limit).map(id->new Item(assets.owned(user,id),referenced(user,id))).toList();
        var last=items.isEmpty()?null:items.getLast().asset();
        return new Page(items,ids.size()>limit?new Cursor(last.createdAt(),last.id()):null);
    }
    public Cleanup remove(long user, String id) {
        return tx.execute(transaction->{
            if(jdbc.queryForList("SELECT id FROM generation_assets WHERE id=? AND user_id=? FOR UPDATE",id,user).isEmpty()) {
                var previous=cleanup(user,id);
                if(previous!=null) return previous;
                throw new NoSuchElementException("参考素材不存在");
            }
            var asset=assets.owned(user,id);
            if(referenced(user,id)) throw new BusinessException(ErrorCode.CONFLICT,"素材已被项目、历史分镜或生成任务引用，不能删除");
            long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO generation_asset_cleanup(id,user_id,object_key,state,next_run_at,created_at) VALUES (?,?,?,'PENDING',?,?)",id,user,asset.objectKey(),now,now);
            // Concurrent new references acquire the same row lock and have a restrictive FK.
            jdbc.update("DELETE FROM generation_assets WHERE id=? AND user_id=?",id,user);
            return cleanup(user,id);
        });
    }
    public List<Cleanup> cleanups(long user) {
        return jdbc.query("SELECT id,state,attempts,created_at,finished_at FROM generation_asset_cleanup WHERE user_id=? ORDER BY created_at DESC,id LIMIT 20",
                (rs,row)->new Cleanup(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getLong(4),rs.getObject(5,Long.class)),user);
    }
    public Cleanup retry(long user,String id) {
        var task=cleanup(user,id);
        if(task==null) throw new NoSuchElementException("素材清理任务不存在");
        if(jdbc.update("UPDATE generation_asset_cleanup SET state='PENDING',attempts=0,lease_token=NULL,next_run_at=? WHERE id=? AND user_id=? AND state='FAILED'",System.currentTimeMillis(),id,user)!=1)
            throw new BusinessException(ErrorCode.CONFLICT,"仅失败的清理任务可以重试");
        return cleanup(user,id);
    }
    private Cleanup cleanup(long user,String id) {
        return jdbc.query("SELECT id,state,attempts,created_at,finished_at FROM generation_asset_cleanup WHERE id=? AND user_id=?",
                (rs,row)->new Cleanup(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getLong(4),rs.getObject(5,Long.class)),id,user).stream().findFirst().orElse(null);
    }
    boolean referenced(long user,String id) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM generation_asset_references WHERE asset_id=?",Integer.class,id)>0) return true;
        // Pre-V11 documents have no normalized FK rows; preserve any historical reference conservatively.
        String token="%"+id+"%";
        return jdbc.queryForObject("SELECT COUNT(*) FROM creative_projects WHERE user_id=? AND brief_json LIKE ?",Integer.class,user,token)>0
            || jdbc.queryForObject("SELECT COUNT(*) FROM storyboard_revisions r JOIN creative_projects p ON p.id=r.project_id WHERE p.user_id=? AND r.draft_json LIKE ?",Integer.class,user,token)>0
            || jdbc.queryForObject("SELECT COUNT(*) FROM generation_tasks WHERE user_id=? AND request_json LIKE ?",Integer.class,user,token)>0;
    }
    @Scheduled(fixedDelayString="${generation.asset-cleanup-delay-ms:30000}",initialDelayString="${generation.asset-cleanup-initial-delay-ms:30000}")
    public void cleanupDue() {
        long now=System.currentTimeMillis();
        var ids=jdbc.queryForList("SELECT id FROM generation_asset_cleanup WHERE state IN ('PENDING','DELETING') AND next_run_at<=? ORDER BY next_run_at,id LIMIT 10",String.class,now);
        for(String id:ids) {
            long claimedAt=System.currentTimeMillis(); String lease=UUID.randomUUID().toString();
            if(jdbc.update("UPDATE generation_asset_cleanup SET state='DELETING',attempts=attempts+1,lease_token=?,next_run_at=? WHERE id=? AND state IN ('PENDING','DELETING') AND next_run_at<=?",lease,claimedAt+300_000,id,claimedAt)!=1) continue;
            var rows=jdbc.queryForList("SELECT object_key,attempts FROM generation_asset_cleanup WHERE id=? AND lease_token=?",id,lease);
            if(rows.isEmpty()) continue;
            var row=rows.getFirst();
            int attempt=((Number)row.get("attempts")).intValue();
            try {
                store.removeReference((String)row.get("object_key"));
                jdbc.update("UPDATE generation_asset_cleanup SET state='SUCCEEDED',finished_at=?,lease_token=NULL WHERE id=? AND state='DELETING' AND lease_token=?",System.currentTimeMillis(),id,lease);
            } catch(Exception failure) {
                jdbc.update("UPDATE generation_asset_cleanup SET state=?,next_run_at=?,lease_token=NULL WHERE id=? AND state='DELETING' AND lease_token=?",
                        attempt>=5?"FAILED":"PENDING",System.currentTimeMillis()+Math.min(300_000,1000L<<Math.min(attempt,8)),id,lease);
            }
        }
    }
    public record Item(GenerationAssetService.Asset asset,boolean referenced) { }
    public record Cursor(long createdAt,String id) { }
    public record Page(List<Item> items,Cursor nextCursor) { }
    public record Cleanup(String id,String state,int attempts,long createdAt,Long finishedAt) { }
}
