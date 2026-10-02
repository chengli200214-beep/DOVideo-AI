package com.example.server.film;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import com.example.server.generation.GenerationArtifactStore;
import com.example.server.generation.GenerationService;
import com.example.server.generation.GenerationTask;
import com.example.server.storyboard.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.util.*;

@Service
public class FilmService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StoryboardRepository projects;
    private final CompositionRepository compositions;
    private final GenerationService generation;
    private final GenerationArtifactStore artifacts;
    private final ObjectMapper json;
    public FilmService(JdbcTemplate jdbc,StoryboardRepository projects,CompositionRepository compositions,GenerationService generation,GenerationArtifactStore artifacts,ObjectMapper json) {
        this.jdbc=jdbc; this.projects=projects; this.compositions=compositions; this.generation=generation; this.artifacts=artifacts; this.json=json;
        transaction=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    public Selection select(long user,String id,SelectionInput input) throws Exception {
        if(input==null || input.revision()<1 || input.expectedSelectionVersion()<0 || input.expectedSelectionVersion()==Integer.MAX_VALUE || input.versionIds()==null
                || input.versionIds().size()<2 || input.versionIds().size()>8 || input.versionIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(input.versionIds()).size()!=input.versionIds().size()) throw new IllegalArgumentException("请为每个镜头选择一个已完成的版本");
        return checked(tx -> {
            var project=lock(user,id); confirmed(project,input.revision());
            var draft=json.readValue(projects.revision(id,input.revision()).json(),StoryboardDraft.class);
            if(draft.shots().size()!=input.versionIds().size()) throw conflict("每个镜头必须选择一个生成版本");
            Map<String,FilmSpec.Clip> chosen=new HashMap<>();
            for(String version: input.versionIds()) {
                var rows=jdbc.queryForList("SELECT * FROM shot_generation_versions WHERE id=? AND project_id=? AND revision=?",version,id,input.revision());
                if(rows.isEmpty()) throw new NoSuchElementException("生成版本不属于当前项目和分镜");
                var row=rows.getFirst(); String shotId=(String)row.get("shot_id");
                var shot=json.readValue((String)row.get("shot_json"),StoryboardDraft.Shot.class);
                var task=generation.get(user,(String)row.get("task_id"));
                if(task.state()!=GenerationTask.State.SUCCEEDED || task.artifactKey()==null || task.artifactSize()==null || task.artifactSha256()==null) throw conflict("仅可选择已归档的镜头版本");
                if(chosen.put(shotId,new FilmSpec.Clip(version,shotId,task.id(),shot.title(),shot.caption(),shot.narration(),task.artifactKey(),task.artifactSize(),task.artifactSha256()))!=null) throw conflict("同一镜头不能选择两个版本");
            }
            List<FilmSpec.Clip> clips=new ArrayList<>();
            for(var shot:draft.shots()) { var clip=chosen.get(shot.id()); if(clip==null) throw conflict("所选版本没有覆盖当前所有镜头"); clips.add(clip); }
            int[] size=switch(draft.shots().getFirst().frameRatio()) { case "16:9" -> new int[]{1280,720}; case "9:16" -> new int[]{720,1280}; case "1:1" -> new int[]{960,960}; default -> throw new IllegalArgumentException("分镜画幅不合法"); };
            // selectionVersion is part of the canonical snapshot; lost saves must match the same immutable parent.
            int next=input.expectedSelectionVersion()+1;
            var spec=new FilmSpec(input.revision(),next,size[0],size[1],true,clips); String payload=json.writeValueAsString(spec);
            var latest=selection(id);
            if(latest!=null && latest.number()==next && latest.snapshot().equals(spec)) return latest;
            if((latest==null?0:latest.number())!=input.expectedSelectionVersion()) throw conflict("镜头选择已变化，请刷新后核对");
            long now=System.currentTimeMillis(); jdbc.update("INSERT INTO film_selections VALUES (?,?,?,?,?)",id,next,input.revision(),payload,now);
            return new Selection(next,spec,now);
        });
    }
    public Overview overview(long user,String id) throws Exception {
        owned(user,id); return new Overview(selection(id),compositions.recent(id).stream().map(this::view).toList());
    }
    public Submitted submit(long user,String id,String key,SubmitInput input) throws Exception {
        if(key==null || !key.matches("[A-Za-z0-9_.:-]{1,128}") || input==null || input.selectionVersion()<1) throw new IllegalArgumentException("请求编号或镜头选择版本不合法");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input)));
        return checked(tx -> {
            var project=lock(user,id); var existing=compositions.byKey(id,key);
            if(existing!=null) { if(!existing.requestHash().equals(hash)) throw conflict("同一请求编号不可用于不同合成参数"); return new Submitted(view(existing),true); }
            Selection latest=selection(id);
            if(latest==null || latest.number()!=input.selectionVersion()) throw conflict("请保存并核对当前镜头选择");
            confirmed(project,latest.snapshot().revision());
            if(compositions.recent(id).size()>=50) throw conflict("项目已有 50 个合成任务，请恢复原失败任务或创建新项目");
            var saved=latest.snapshot(); var spec=new FilmSpec(saved.revision(),saved.selectionVersion(),saved.width(),saved.height(),input.burnCaptions(),saved.clips());
            String job=UUID.randomUUID().toString(); compositions.insert(job,id,user,key,hash,json.writeValueAsString(spec),System.currentTimeMillis());
            return new Submitted(view(compositions.byId(job)),false);
        });
    }
    public Detail get(long user,String id,String job) throws Exception {
        var task=ownedTask(user,id,job); return new Detail(view(task),json.readValue(task.snapshotJson(),FilmSpec.class),compositions.events(job));
    }
    public View retry(long user,String id,String job) {
        ownedTask(user,id,job); if(!compositions.retry(job,user,System.currentTimeMillis())) throw conflict("仅失败的合成任务可恢复"); return view(compositions.byId(job));
    }
    public String artifact(long user,String id,String job) {
        var task=ownedTask(user,id,job); if(!task.state().equals("SUCCEEDED") || task.artifactKey()==null) throw conflict("成片尚未归档完成"); return artifacts.readableUrl(task.artifactKey());
    }
    public Download download(long user,String id,String job) throws Exception {
        var task=ownedTask(user,id,job); if(!task.state().equals("SUCCEEDED") || task.artifactKey()==null) throw conflict("成片尚未归档完成");
        return new Download(artifacts.openArtifact(task.artifactKey()),task.artifactSize());
    }
    private Selection selection(String id) throws Exception {
        var rows=jdbc.queryForList("SELECT * FROM film_selections WHERE project_id=? ORDER BY selection_version DESC LIMIT 1",id);
        if(rows.isEmpty()) return null; var row=rows.getFirst(); return new Selection(((Number)row.get("selection_version")).intValue(),json.readValue((String)row.get("snapshot_json"),FilmSpec.class),((Number)row.get("created_at")).longValue());
    }
    private StoryboardRepository.Project owned(long user,String id) { var project=projects.byId(id,user); if(project==null) throw new NoSuchElementException("创作项目不存在"); return project; }
    private StoryboardRepository.Project lock(long user,String id) { if(jdbc.queryForList("SELECT id FROM creative_projects WHERE id=? AND user_id=? FOR UPDATE",id,user).isEmpty()) throw new NoSuchElementException("创作项目不存在"); return owned(user,id); }
    private CompositionRepository.Task ownedTask(long user,String id,String job) { owned(user,id); var task=compositions.byId(job); if(task==null || task.userId()!=user || !task.projectId().equals(id)) throw new NoSuchElementException("合成任务不存在"); return task; }
    private void confirmed(StoryboardRepository.Project project,int revision) { if(!"CONFIRMED".equals(project.status()) || project.latestRevision()!=revision || !Objects.equals(project.confirmedRevision(),revision)) throw conflict("仅可合成当前已确认的分镜"); }
    private View view(CompositionRepository.Task task) { return new View(task.id(),task.state(),task.attempts(),task.errorCode(),task.artifactSize(),task.artifactSha256(),task.metadataJson(),task.createdAt(),task.updatedAt()); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT,message); }
    private interface Work<T> { T run(Object tx) throws Exception; }
    private <T> T checked(Work<T> work) throws Exception { try { return transaction.execute(tx -> { try { return work.run(tx); } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new CheckedFailure(e); } }); } catch(CheckedFailure e) { throw (Exception)e.getCause(); } }
    private static class CheckedFailure extends RuntimeException { CheckedFailure(Exception cause) { super(cause); } }
    public record SelectionInput(int revision,int expectedSelectionVersion,List<String> versionIds) { }
    public record SubmitInput(int selectionVersion,boolean burnCaptions) { }
    public record Selection(int number,FilmSpec snapshot,long createdAt) { }
    public record View(String id,String state,int attempts,String errorCode,Long artifactSize,String artifactSha256,String metadataJson,long createdAt,long updatedAt) { }
    public record Overview(Selection selection,List<View> tasks) { }
    public record Submitted(View task,boolean reused) { }
    public record Detail(View task,FilmSpec input,List<CompositionRepository.Event> events) { }
    public record Download(java.io.InputStream stream,long size) { }
}
